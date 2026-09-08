#!/usr/bin/env python3
"""
Import the Marel product catalogue from the scraped Excel exports into the
family -> type -> product hierarchy.

Sources
  MAREL_MASTER.xlsx
     · sheet "Families" -> product_families
     · sheet "Products" -> product_types   (one row per TYPE, not per article)
  MAREL_SVI_PROIZVODI.xlsx
     · sheet "Proizvodi" -> products        (one row per ARTICLE)

Linking
  · product.kod  == product_type.code  (within the same family)  -> product_type_id
  · subtype       = tip with the leading kod stripped ("CuCPST 10.6" -> "10.6")
  · catalog_number = kataloski_broj      (see duplicate handling below)

Data-quality handling (decided with the owner)
  · type rows with a blank or absurdly long (>50) code are skipped (garbage).
  · duplicate (family, code) types collapse to one (first wins).
  · products whose kod has no matching type import with product_type_id NULL.
  · near-identical product rows (same family+kod+tip+name) collapse to one.
  · duplicate catalog numbers: first product keeps it, the rest get
    catalog_number = NULL. Every affected row is reported.

Idempotent: families/types use ON CONFLICT DO NOTHING on their unique indexes;
products are skipped when one already exists with the same
(product_type_id, lower(name), subtype). Safe to re-run.

Usage
  python import_catalog.py            # dry-run: compute + print plan, write nothing
  python import_catalog.py --commit   # do the dry-run summary, then commit in ONE tx
"""
import os
import re
import sys
from collections import defaultdict

import openpyxl
import psycopg2

# --- config ------------------------------------------------------------------
EXPORT_DIR = "/Users/aleksandarparipovic/Documents/Projects/marel_app/marel-scraper/Marel_Export"
MASTER = os.path.join(EXPORT_DIR, "MAREL_MASTER.xlsx")
ALL_PRODUCTS = os.path.join(EXPORT_DIR, "MAREL_SVI_PROIZVODI.xlsx")

BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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


# --- excel helpers -----------------------------------------------------------
def read_sheet(path, sheet):
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    ws = wb[sheet]
    it = ws.iter_rows(values_only=True)
    header = next(it)
    rows = [dict(zip(header, r)) for r in it if any(c is not None for c in r)]
    wb.close()
    return rows


def s(v):
    """Trim to a clean string, or None when empty."""
    if v is None:
        return None
    t = str(v).strip()
    return t or None


def derive_subtype(kod, tip):
    """tip with the leading kod stripped; whole tip if it does not start with kod."""
    kod = (kod or "").strip()
    tip = (tip or "").strip()
    if not tip:
        return None
    if kod and tip.lower().startswith(kod.lower()):
        rest = tip[len(kod):].strip()
        return rest or None
    return tip  # unusual source row: keep the distinguishing label whole


# --- plan builders -----------------------------------------------------------
def build_plan():
    fam_rows = read_sheet(MASTER, "Families")
    type_rows = read_sheet(MASTER, "Products")
    prod_rows = read_sheet(ALL_PRODUCTS, "Proizvodi")

    report = {"type_skipped": [], "type_collapsed": [], "prod_no_type": [],
              "prod_collapsed": [], "catalog_nulled": [], "prod_indistinct": []}

    # ---- families (Families sheet is canonical + gives display order) --------
    families = []            # (name, description, sort_order)
    fam_seen = set()
    order = 0
    for r in fam_rows:
        name = s(r.get("Family"))
        if not name or name.lower() in fam_seen:
            continue
        order += 1
        families.append((name, None, order))
        fam_seen.add(name.lower())
    # defensively add any family referenced elsewhere but missing from the sheet
    for r in type_rows + prod_rows:
        name = s(r.get("Family") or r.get("porodica_proizvoda"))
        if name and name.lower() not in fam_seen:
            order += 1
            families.append((name, None, order))
            fam_seen.add(name.lower())

    # ---- types (Products sheet) ---------------------------------------------
    types = []               # (family_name, name, code, description, note, standard, sort_order)
    type_key_seen = set()    # (family_lower, code_lower)
    per_fam_order = defaultdict(int)
    for r in type_rows:
        fam = s(r.get("Family"))
        code = s(r.get("Code"))
        name = s(r.get("Product"))
        if not fam or not code or not name:
            report["type_skipped"].append((fam, name, code, "blank family/code/name"))
            continue
        if len(code) > 50:
            report["type_skipped"].append((fam, name, code[:40] + "...", "code longer than 50 chars"))
            continue
        key = (fam.lower(), code.lower())
        if key in type_key_seen:
            report["type_collapsed"].append((fam, code, name))
            continue
        type_key_seen.add(key)
        per_fam_order[fam.lower()] += 1
        types.append((fam, name, code, s(r.get("Description")),
                      s(r.get("Note")), s(r.get("Standards")),
                      per_fam_order[fam.lower()]))

    # ---- products (Proizvodi sheet) -----------------------------------------
    type_lookup = {(fam.lower(), code.lower()) for (fam, _n, code, *_ ) in types}

    products = []            # dict per product
    prod_key_seen = set()    # identity: catalog (or family) + kod + tip + name
    eff_seen = set()         # what the product MODEL can tell apart, after nulling
    catalog_seen = set()     # lower(catalog_number)
    for r in prod_rows:
        fam = s(r.get("porodica_proizvoda"))
        name = s(r.get("naziv_proizvoda"))
        kod = s(r.get("kod"))
        tip = s(r.get("tip"))
        catalog = s(r.get("kataloski_broj"))
        if not fam or not name:
            continue

        # A product's identity is its catalogue number (one number = one article).
        # Two rows are the same article only when the number AND the kod/tip/name
        # agree: same number + different name/tip is a source ERROR (two real
        # products sharing a number — kept, catalog nulled below); different
        # numbers are always different articles even when name/tip match (the
        # same clamp in another size). When a row has no catalogue number we
        # fall back to family+kod+tip+name. Collapsing here therefore only ever
        # merges the SAME article listed twice (e.g. under two families).
        ident = (catalog.lower(),) if catalog else (fam.lower(),)
        dedup_key = ident + ((kod or "").lower(), (tip or "").lower(), name.lower())
        if dedup_key in prod_key_seen:
            report["prod_collapsed"].append((fam, name, kod, tip, catalog))
            continue
        prod_key_seen.add(dedup_key)

        has_type = kod and (fam.lower(), kod.lower()) in type_lookup
        if not has_type:
            report["prod_no_type"].append((fam, name, kod, tip, catalog))

        # subtype: strip the leading kod only when a type carries it. For an
        # uncategorised product there is no type to hold the code, so the full
        # tip is the article's only distinguisher and must be kept whole
        # (CBS/CNA 1S 120 vs CBS/CN 1S 120 both differ only by their kod).
        subtype = derive_subtype(kod, tip) if has_type else tip

        # duplicate catalog number: first wins, the rest are nulled + reported
        keep_catalog = catalog
        if catalog:
            if catalog.lower() in catalog_seen:
                keep_catalog = None
                report["catalog_nulled"].append((fam, name, kod, tip, catalog))
            else:
                catalog_seen.add(catalog.lower())

        # After nulling, can the model still tell this article from an earlier
        # one? A product is distinguished by family+kod+subtype and, when it has
        # one, its catalogue number. Two DIFFERENT articles that share
        # family+kod+subtype AND both lost their number to earlier collisions
        # are now indistinguishable — a dead end caused by the source reusing
        # catalogue numbers across unrelated products. Report, don't insert a
        # phantom twin.
        eff_key = (fam.lower(), (kod or "").lower(), (tip or "").lower(),
                   name.lower(), keep_catalog.lower() if keep_catalog else None)
        if eff_key in eff_seen:
            report["prod_indistinct"].append((fam, name, kod, tip, catalog))
            continue
        eff_seen.add(eff_key)

        products.append({
            "family": fam, "name": name, "kod": kod, "tip": tip,
            "type_key": (fam.lower(), kod.lower()) if has_type else None,
            "subtype": subtype,
            "catalog_number": keep_catalog,
        })

    return families, types, products, report


def print_summary(families, types, products, report):
    print("=" * 70)
    print("IMPORT PLAN")
    print("=" * 70)
    print(f"  families to ensure : {len(families)}")
    print(f"  types to ensure    : {len(types)}   "
          f"(skipped {len(report['type_skipped'])}, "
          f"collapsed {len(report['type_collapsed'])})")
    print(f"  products to insert : {len(products)}   "
          f"(same article listed twice {len(report['prod_collapsed'])}, "
          f"indistinct after catalog nulled {len(report['prod_indistinct'])})")
    print(f"    - with a type    : {sum(1 for p in products if p['type_key'])}")
    print(f"    - uncategorised  : {sum(1 for p in products if not p['type_key'])}")
    print(f"    - catalog# nulled (duplicate): {len(report['catalog_nulled'])}")
    print()

    def dump(title, rows, fmt):
        print(f"--- {title} ({len(rows)}) ---")
        for r in rows:
            print("   ", fmt(r))
        print()

    dump("TYPES SKIPPED (garbage code)", report["type_skipped"],
         lambda r: f"[{r[0]}] name={r[1]!r} code={r[2]!r} :: {r[3]}")
    dump("TYPES COLLAPSED (duplicate family+code)", report["type_collapsed"],
         lambda r: f"[{r[0]}] code={r[1]!r} name={r[2]!r}")
    dump("PRODUCTS WITH NO MATCHING TYPE (product_type_id=NULL)", report["prod_no_type"],
         lambda r: f"[{r[0]}] {r[1]!r} kod={r[2]!r} tip={r[3]!r} cat={r[4]}")
    dump("PRODUCTS COLLAPSED (near-identical rows dropped)", report["prod_collapsed"],
         lambda r: f"[{r[0]}] {r[1]!r} kod={r[2]!r} tip={r[3]!r} cat={r[4]}")
    dump("CATALOG NUMBERS NULLED (duplicate; kept on first product only)", report["catalog_nulled"],
         lambda r: f"cat={r[4]} -> [{r[0]}] {r[1]!r} kod={r[2]!r} tip={r[3]!r}")
    dump("PRODUCTS DROPPED - INDISTINCT after catalog nulled (source reused the number)",
         report["prod_indistinct"],
         lambda r: f"[{r[0]}] {r[1]!r} kod={r[2]!r} tip={r[3]!r} origcat={r[4]}")


# --- writers -----------------------------------------------------------------
def commit(conn, families, types, products):
    cur = conn.cursor()

    # families
    for name, desc, order in families:
        cur.execute(
            "INSERT INTO product_families (name, description, sort_order) "
            "VALUES (%s, %s, %s) "
            "ON CONFLICT (lower((name)::text)) DO NOTHING",
            (name, desc, order),
        )
    cur.execute("SELECT id, lower(name) FROM product_families")
    fam_id = {n: i for (i, n) in cur.fetchall()}

    # types
    for fam, name, code, desc, note, standard, order in types:
        cur.execute(
            "INSERT INTO product_types "
            "(family_id, name, code, description, note, standard, sort_order) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s) "
            "ON CONFLICT (family_id, lower((code)::text)) DO NOTHING",
            (fam_id[fam.lower()], name, code, desc, note, standard, order),
        )
    cur.execute("SELECT id, family_id, lower(code) FROM product_types")
    fam_by_id = {i: f for (f, i) in fam_id.items()}
    type_id = {(fam_by_id[famid], code): tid for (tid, famid, code) in cur.fetchall()}

    inserted = 0
    skipped_existing = 0
    for p in products:
        ptid = type_id.get(p["type_key"]) if p["type_key"] else None
        # idempotency (safe re-runs): a product with a catalogue number is
        # identified by it; one without falls back to name+type+subtype. Keying
        # on the catalogue number is what keeps two different-size articles that
        # share a name+subtype from collapsing into one on re-run.
        if p["catalog_number"]:
            cur.execute(
                "SELECT id FROM products WHERE lower(catalog_number) = lower(%s)",
                (p["catalog_number"],),
            )
        else:
            cur.execute(
                "SELECT id FROM products "
                "WHERE lower(product_name) = lower(%s) "
                "  AND product_type_id IS NOT DISTINCT FROM %s "
                "  AND subtype IS NOT DISTINCT FROM %s "
                "  AND catalog_number IS NULL",
                (p["name"], ptid, p["subtype"]),
            )
        if cur.fetchone():
            skipped_existing += 1
            continue
        cur.execute(
            "INSERT INTO products "
            "(product_name, product_code, product_type_id, catalog_number, subtype, is_active) "
            "VALUES (%s, NULL, %s, %s, %s, true)",
            (p["name"], ptid, p["catalog_number"], p["subtype"]),
        )
        inserted += 1

    conn.commit()
    print("=" * 70)
    print("COMMITTED")
    print(f"  products inserted        : {inserted}")
    print(f"  products already present  : {skipped_existing}")
    cur.execute("SELECT count(*) FROM product_families")
    print(f"  product_families total    : {cur.fetchone()[0]}")
    cur.execute("SELECT count(*) FROM product_types")
    print(f"  product_types total       : {cur.fetchone()[0]}")
    cur.execute("SELECT count(*) FROM products")
    print(f"  products total            : {cur.fetchone()[0]}")
    cur.close()


def main():
    do_commit = "--commit" in sys.argv
    families, types, products, report = build_plan()
    print_summary(families, types, products, report)
    if not do_commit:
        print("DRY-RUN ONLY — nothing written. Re-run with --commit to apply.")
        return
    conn = connect()
    try:
        commit(conn, families, types, products)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
