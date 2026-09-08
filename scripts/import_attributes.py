#!/usr/bin/env python3
"""
Import product-type attribute SCHEMAS and per-product attribute VALUES from the
scraped exports into the catalogue that import_catalog.py already built.

Model (already in the DB)
  product_type_attributes   the schema — one row per attribute of a type
  product_attribute_values  the values — one row per (product, attribute)

Sources
  MAREL_SVI_PROIZVODI.xlsx, sheet "Proizvodi"
    · column "dodatne_informacije" — a JSON object {attribute label: value}
      per ARTICLE. This is the authoritative source: schema and values come
      from the same place, so they always agree.
  Marel_Export/<family>/<type>/product.json
    · "description", "note", "standards" for the TYPE (used to fill those
      columns on product_types when they are still empty)
    · the table "headers" only give a canonical ORDER for the attributes; the
      42 types with no table still get their schema from dodatne_informacije.

Linking
  · each xlsx row -> DB product by catalog_number (1:1, verified 1195/1195).
  · the product's product_type_id -> which type the attribute belongs to.
  · a product with no type (product_type_id NULL) can hold no values -> skipped.

Attribute naming (decided with the owner)
  · name = the Serbian side of the bilingual label (before " / "),
    with a trailing "(unit)" split off into `unit`.
    "Opseg upotrebe (mm²) / Range of use (mm²)" -> name "Opseg upotrebe", unit "mm²"
  · data_type = NUMBER when every non-blank value of that attribute parses as a
    number, else TEXT.
  · near-identical labels that collapse to the same name within one type merge
    into a single attribute (the unique index is (type, lower(name))).

Idempotent
  · attributes: ON CONFLICT (product_type_id, lower(name)) DO NOTHING.
  · values: ON CONFLICT (product_id, attribute_id) DO UPDATE SET value.
  · description/note/standard: only filled when currently NULL/blank.
  Safe to re-run.

Usage
  python import_attributes.py            # dry-run: compute + print plan, write nothing
  python import_attributes.py --commit   # dry-run summary, then commit in ONE tx
"""
import json
import os
import re
import sys
from collections import defaultdict, OrderedDict

import openpyxl
import psycopg2

# --- config ------------------------------------------------------------------
EXPORT_DIR = "/Users/aleksandarparipovic/Documents/Projects/marel_app/marel-scraper/Marel_Export"
ALL_PRODUCTS = os.path.join(EXPORT_DIR, "MAREL_SVI_PROIZVODI.xlsx")
BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NAME_MAX = 100
UNIT_MAX = 30
VALUE_MAX = 255


def db_password():
    env = os.path.join(BACKEND_DIR, ".env")
    with open(env) as fh:
        for line in fh:
            if line.startswith("DB_PASSWORD="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("DB_PASSWORD not found in .env")


def connect():
    return psycopg2.connect(
        host="localhost", port=5432, dbname="marel_app",
        user="aleksandarparipovic", password=db_password(),
    )


# --- parsing helpers ---------------------------------------------------------
# Only these trailing "(token)" are a UNIT; everything else (Al, Cu, Imax,
# "duplo kidanje", …) is a qualifier and stays part of the name, so that e.g.
# "Broj presovanja meh./hidr. (Al)" and "(Cu)" remain two distinct attributes.
UNITS = {
    "mm", "mm²", "mm2", "cm", "dm", "m", "km", "m²",
    "kg", "g", "mg", "t", "kom", "pcs", "kpl",
    "a", "ma", "ka", "v", "vac", "kv", "w", "kw",
    "n", "nm", "kn", "%", "°", "°c", "µm", "um", "hz", "bar", "mpa",
}

# Some labels put the English translation after a plain SPACE instead of " / ".
# When there is no " / " to split on, the English half is cut at the first of
# these markers. They do not occur inside the Serbian half.
_ENGLISH = [
    "Number", "Weight", "Packaging", "Conductor", "Cable", "Assembly",
    "Indicator", "Die", "Type", "Current", "Range", "Color", "Colour", "Stud",
    "Bolt", "Cap", "Palm", "Hole", r"Max\.", r"Min\.", r"No\.?", "Cross-section",
    "Cross", "Section", "Diameter", "Length", "Width", "Thickness", "Force",
    "Voltage", "Material", "Matrix", "breaking", "load", "carrying", "capacity",
    "diameter", "crimpings", "bolts", "service", "Customer", "according",
    "of", "for", "and",
]
_ENGLISH_RE = re.compile(r"\b(?:%s)\b" % "|".join(_ENGLISH))


def parse_name_unit(label):
    """Bilingual label -> (name, unit). Serbian side (before ' / ' or the English
    marker for space-joined labels), with a trailing unit split into `unit`."""
    s = str(label).strip()
    had_slash = " / " in s
    serbian = re.split(r"\s+/\s+", s, maxsplit=1)[0].strip()
    if not had_slash:
        m = _ENGLISH_RE.search(serbian)
        if m and m.start() > 0:
            serbian = serbian[: m.start()].strip()

    unit = None
    name = serbian
    m = re.search(r"\(([^()]*)\)\s*$", serbian)
    if m and m.group(1).strip().lower() in UNITS:
        unit = m.group(1).strip()
        name = serbian[: m.start()].strip()
    else:
        # a short dimension symbol followed by a bare unit: "D mm", "L1 mm"
        m2 = re.match(r"^([A-Za-z]{1,3}[0-9]?)\s+([A-Za-zµ²]+)$", serbian)
        if m2 and m2.group(2).lower() in UNITS:
            name, unit = m2.group(1), m2.group(2)

    if not name:  # a label that was nothing but "(unit)" — keep the raw side
        name = serbian or s
    name = name[:NAME_MAX].strip()
    if unit:
        unit = unit[:UNIT_MAX].strip() or None
    return name, unit


def norm_key(name):
    """Match/merge labels that differ only in case or spacing."""
    return re.sub(r"\s+", " ", str(name).strip().lower())


def is_number(v):
    if v is None:
        return False
    t = str(v).strip().replace("\xa0", "").replace(" ", "").replace(",", ".")
    if t == "":
        return False
    try:
        float(t)
        return True
    except ValueError:
        return False


# --- product.json (per type: description / note / standard / header order) ---
def load_product_json():
    """code -> {'desc','note','standard','order': [normalized attr name, ...]}."""
    import glob
    out = {}
    for f in glob.glob(os.path.join(EXPORT_DIR, "*", "*", "product.json")):
        try:
            d = json.load(open(f))
        except Exception:
            continue
        code = (d.get("code") or "").strip()
        if not code:
            continue
        headers = []
        tabs = d.get("tables") or []
        if tabs and isinstance(tabs[0], dict):
            headers = tabs[0].get("headers") or []
        # attributes = headers after the 'kod/code' column
        cut = 2
        for i, h in enumerate(headers):
            hl = str(h).lower()
            if "kod" in hl and "code" in hl:
                cut = i + 1
                break
        order = [norm_key(parse_name_unit(h)[0]) for h in headers[cut:]]
        # first code wins (types can repeat a code across families; desc is the same)
        out.setdefault(code, {
            "desc": (d.get("description") or "").strip(),
            "note": (d.get("note") or "").strip(),
            "standard": (d.get("standards") or "").strip(),
            "order": order,
        })
    return out


# --- build the plan ----------------------------------------------------------
def build_plan():
    pj = load_product_json()

    # DB: article catalog -> (product_id, type_id); type_id -> code.
    conn = connect()
    cur = conn.cursor()
    cur.execute("""
        select p.id, p.catalog_number, p.product_type_id
        from products p
        where p.archived_at is null and p.catalog_number is not null
    """)
    prod_by_cat = {}
    for pid, cat, tid in cur.fetchall():
        prod_by_cat[str(cat).strip()] = (pid, tid)

    cur.execute("select id, code from product_types where archived_at is null")
    code_by_type = {tid: code for tid, code in cur.fetchall()}
    conn.close()

    # xlsx rows -> group by type.
    wb = openpyxl.load_workbook(ALL_PRODUCTS, read_only=True, data_only=True)
    ws = wb["Proizvodi"]
    it = ws.iter_rows(values_only=True)
    H = list(next(it))
    ix = {h: i for i, h in enumerate(H)}

    # per type_id: OrderedDict normname -> {'name','unit','values':[...],'nums':bool}
    type_attrs = defaultdict(OrderedDict)
    # values: list of (product_id, type_id, normname, value)
    pending_values = []

    report = {
        "rows": 0, "rows_with_json": 0, "unmatched_catalog": 0,
        "no_type": 0, "bad_json": 0, "values_seen": 0,
        "no_type_examples": set(), "unmatched_examples": set(),
    }

    for r in it:
        report["rows"] += 1
        cat = r[ix["kataloski_broj"]]
        dd = r[ix["dodatne_informacije"]]
        if not dd:
            continue
        report["rows_with_json"] += 1
        cat = str(cat).strip() if cat is not None else ""
        hit = prod_by_cat.get(cat)
        if not hit:
            report["unmatched_catalog"] += 1
            if len(report["unmatched_examples"]) < 6:
                report["unmatched_examples"].add(cat)
            continue
        pid, tid = hit
        if tid is None:
            report["no_type"] += 1
            if len(report["no_type_examples"]) < 6:
                report["no_type_examples"].add(str(r[ix["kod"]]))
            continue
        try:
            obj = json.loads(dd)
        except Exception:
            report["bad_json"] += 1
            continue
        for label, value in obj.items():
            name, unit = parse_name_unit(label)
            nk = norm_key(name)
            slot = type_attrs[tid].get(nk)
            if slot is None:
                slot = {"name": name, "unit": unit, "nums": True, "seen": False}
                type_attrs[tid][nk] = slot
            if unit and not slot["unit"]:
                slot["unit"] = unit
            val = "" if value is None else str(value).strip()
            if val != "":
                report["values_seen"] += 1
                if not is_number(val):
                    slot["nums"] = False
                pending_values.append((pid, tid, nk, val[:VALUE_MAX]))

    # order attributes per type using product.json header order when available.
    ordered_attrs = {}  # type_id -> [ (normname, name, unit, data_type, sort_order) ]
    for tid, attrs in type_attrs.items():
        order = pj.get(code_by_type.get(tid, ""), {}).get("order", [])
        order_ix = {nk: i for i, nk in enumerate(order)}
        items = list(attrs.items())

        def sort_key(item):
            nk, _ = item
            return (order_ix.get(nk, 10_000), list(attrs.keys()).index(nk))

        items.sort(key=sort_key)
        rows = []
        for so, (nk, slot) in enumerate(items):
            rows.append((nk, slot["name"], slot["unit"],
                         "NUMBER" if slot["nums"] else "TEXT", so))
        ordered_attrs[tid] = rows

    # description/note/standard to fill (per type, only where product.json has text).
    type_meta = {}
    for tid, code in code_by_type.items():
        info = pj.get(code)
        if info and (info["desc"] or info["note"] or info["standard"]):
            type_meta[tid] = (info["desc"], info["note"], info["standard"])

    return ordered_attrs, pending_values, type_meta, code_by_type, report


def print_summary(ordered_attrs, pending_values, type_meta, code_by_type, report):
    total_attrs = sum(len(v) for v in ordered_attrs.values())
    print("=" * 64)
    print("ATRIBUTI — PLAN")
    print("=" * 64)
    print(f"xlsx redova:                     {report['rows']}")
    print(f"  sa dodatne_informacije:        {report['rows_with_json']}")
    print(f"  bez para u bazi (catalog):     {report['unmatched_catalog']}  {sorted(report['unmatched_examples'])}")
    print(f"  proizvod bez tipa (preskočeno):{report['no_type']}  kod={sorted(report['no_type_examples'])}")
    print(f"  neispravan JSON:               {report['bad_json']}")
    print("-" * 64)
    print(f"tipova koji dobijaju atribute:   {len(ordered_attrs)}")
    print(f"definicija atributa (ukupno):    {total_attrs}")
    print(f"vrednosti za upis:               {len(pending_values)}")
    print(f"tipova sa description/note/std:  {len(type_meta)}")
    # a couple of example schemas
    print("-" * 64)
    print("primeri šema:")
    shown = 0
    for tid, rows in ordered_attrs.items():
        print(f"  tip #{tid} ({code_by_type.get(tid)}): "
              + ", ".join(f"{n}[{u or '-'}|{dt}]" for _, n, u, dt, _ in rows[:6])
              + (" …" if len(rows) > 6 else ""))
        shown += 1
        if shown >= 6:
            break
    print("=" * 64)


def commit(conn, ordered_attrs, pending_values, type_meta):
    cur = conn.cursor()

    # 1) attribute definitions; capture (type_id, normname) -> attr_id
    attr_id = {}
    ins_attr = 0
    for tid, rows in ordered_attrs.items():
        for nk, name, unit, data_type, so in rows:
            cur.execute("""
                insert into product_type_attributes
                    (product_type_id, name, unit, data_type, sort_order, is_required, is_active)
                values (%s, %s, %s, %s, %s, false, true)
                on conflict (product_type_id, lower(name)) do nothing
            """, (tid, name, unit, data_type, so))
            ins_attr += cur.rowcount
            cur.execute("""
                select id from product_type_attributes
                where product_type_id = %s and lower(name) = lower(%s)
            """, (tid, name))
            attr_id[(tid, nk)] = cur.fetchone()[0]

    # 2) description / note / standard — only where currently empty
    upd_meta = 0
    for tid, (desc, note, standard) in type_meta.items():
        cur.execute("""
            update product_types set
                description = case when coalesce(description,'') = '' then nullif(%s,'') else description end,
                note        = case when coalesce(note,'')        = '' then nullif(%s,'') else note        end,
                standard    = case when coalesce(standard,'')    = '' then nullif(%s,'') else standard    end
            where id = %s
        """, (desc, note, standard, tid))
        upd_meta += cur.rowcount

    # 3) values
    ins_val = 0
    for pid, tid, nk, value in pending_values:
        aid = attr_id.get((tid, nk))
        if aid is None:
            continue
        cur.execute("""
            insert into product_attribute_values (product_id, product_type_attribute_id, value)
            values (%s, %s, %s)
            on conflict (product_id, product_type_attribute_id) do update set value = excluded.value
        """, (pid, aid, value))
        ins_val += 1

    conn.commit()
    print(f"UPISANO: {ins_attr} novih atributa, {upd_meta} tipova (opis/note/std), {ins_val} vrednosti.")

    cur.execute("select count(*) from product_type_attributes")
    print("  product_type_attributes ukupno:", cur.fetchone()[0])
    cur.execute("select count(*) from product_attribute_values")
    print("  product_attribute_values ukupno:", cur.fetchone()[0])


def main():
    do_commit = "--commit" in sys.argv
    ordered_attrs, pending_values, type_meta, code_by_type, report = build_plan()
    print_summary(ordered_attrs, pending_values, type_meta, code_by_type, report)
    if not do_commit:
        print("DRY-RUN ONLY — ništa nije upisano. Pokreni sa --commit da primeniš.")
        return
    conn = connect()
    try:
        commit(conn, ordered_attrs, pending_values, type_meta)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
