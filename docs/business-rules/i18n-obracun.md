# Višejezični obračun — odluka i plan

> **Status: ODLUČENO, nije implementirano.**
> Ovo je autoritativan dokument za višejezičnost. Pokriva **oba repozitorijuma**
> (`marel-app-backend-server` i `marel-app`), iako živi ovde jer su sva poslovna
> pravila u `docs/business-rules/`.
>
> **Srodni dokumenti:**
> - [`compensation-schemes-and-category-localization.md`](compensation-schemes-and-category-localization.md) §11 —
>   opisuje translation tabele koje **već postoje** (`work_code_category_translations`,
>   `payroll_adjustment_category_translations`) i pravilo `COALESCE(prevod, master)`.
>   Ovaj plan ih **proširuje, ne menja**.
> - [`i18n-analiza-i-odbacene-opcije.md`](i18n-analiza-i-odbacene-opcije.md) —
>   inventar zatečenog stanja i **odbačena** opcija prevođenja celog interfejsa.
>   Pročitaj ga pre nego što predložiš drugačiji pristup; verovatno je već razmotren.
>
> **Sažetak odluke u tri rečenice.** Prevodi se samo obračunski PDF; korisnički
> interfejs ostaje na srpskoj latinici. Skup jezika je fiksan (`sr-Latn`, `sr-Cyrl`,
> `en`, `ru`) i isporučuje ga programer — administrator ne dodaje jezike.
> Statičke labele dokumenta su JSON resursi u frontend repozitorijumu, a nazivi
> kategorija ostaju u bazi, gde već jesu.

---


**Obim:** prevodi se **samo obračun**. Korisnički interfejs aplikacije ostaje `sr-Latn`, nepromenjen.
**Jezici:** fiksan skup koji isporučuje programer — `sr-Latn`, `sr-Cyrl`, `en`, `ru`. Nov jezik ide uz nov build.
**Datum odluke:** 2026-08-05.
**Analizirano stanje:** `marel-app-backend-server` @ `6fddfb5`, `marel-app` @ `f4c534b`.

> **Razmatrana pa odbačena varijanta:** `locales` tabela kojom upravlja administrator,
> uz labele dokumenta u bazi, tako da se nov jezik dodaje bez novog build-a.
> Odbačena jer je usko grlo čovek koji zna jezik, a ne isporuka — a aplikacija ionako
> redovno izlazi u novim build-ovima. Fiksan skup uz to daje garanciju u vreme
> kompajliranja (§5.1) koju baza ne može. Vraćanje na tu varijantu je kasnije mala
> migracija: ~30 labela iz JSON-a u tabelu.

---

## 1. Podela: šta gde živi

| Šta | Gde | Ko menja | Kako se greška hvata |
|---|---|---|---|
| **~30 statičkih labela obračuna** („Ime i prezime:", „SVEGA ZA ISPLATU:") | `features/payrolls/i18n/{locale}.json` | programer, kroz PR | **build pada** ako jezik nema labelu (§5.1) |
| **Nazivi radnih kategorija** („I, II smena") | `work_code_category_translations` ✅ postoji | administrator | fallback na srpski + izveštaj o nedostajućim |
| **Nazivi stavki obračuna** („Topli obrok", „Prevoz") | `payroll_adjustment_category_translations` ✅ postoji | administrator | isto |
| **Format brojeva i naziv meseca** | `Intl` sa jezikom dokumenta | — | test po jeziku |
| **Napomene** (radnika, direktora) | slobodan tekst | čovek koji ga je uneo | **ne prevodi se nikad** |

Tvoj princip — *UI tekst u JSON, poslovni podaci u bazu* — ovde važi u punom obimu. Labele obračunskog obrasca su tekst programera; nazivi kategorija su poslovni podaci koje administrator održava i koji **već jesu** u bazi sa svojim translation tabelama.

### Zašto je fiksan skup jezika ovde ispravan

- **Usko grlo je prevodilac, ne deployment.** U oba slučaja nekome treba ~30 labela + ~40 naziva na ruskom. Kad postoji, „admin ih otkuca" i „programer ih stavi u `ru.json`" su podjednako lak korak.
- **JSON u gitu dobija code review.** Pogrešno preveden „SVEGA ZA ISPLATU" na obračunu je stvaran problem; u PR-u ga neko pregleda, u admin ekranu u petak u 17h ne pregleda niko.
- **TypeScript garantuje potpunost** (§5.1) — nemoguće je isporučiti jezik kojem fali labela. Baza to ne može; tamo nedostajuća labela tiho padne na srpski.
- **Nestaje ceo režim otkaza:** administrator doda `uk`, popuni 3 od 47 polja, odštampa obračun na dva jezika.

**Kada bi suprotno bilo tačno:** da je aplikacija zamrznuta i da se build pravi jednom godišnje, ili da nema programera na raspolaganju. Prelazak nazad je kasnije mala migracija — 30 labela iz JSON-a u tabelu. Vrata se ne zatvaraju.

---

## 2. Šta se konkretno prevodi na obračunu

Iz `PayrollPdf.tsx` i `PayrollRunItemDetailResponse`:

| # | Element | Primer | Izvor | Postoji? |
|---|---|---|---|---|
| 1 | Statičke labele (~30) | „Sati u normi", „Pregled razduženja" | `{locale}.json` | ❌ gradi se |
| 2 | Nazivi radnih kategorija | „I, II smena", „Galvanizacija" | `work_code_category_translations` | ✅ postoji, fale redovi |
| 3 | Nazivi stavki obračuna | „Topli obrok", „Prevoz", „Kredit" | `payroll_adjustment_category_translations` | ✅ postoji, fale redovi |
| 4 | Naziv meseca | „avgust 2026" | `Intl.DateTimeFormat` | ⚠️ `MONTHS_SR` niz u `EmployeePayroll.tsx:242` |
| 5 | Format brojeva | `1.234,50` / `1 234,50` | `Intl.NumberFormat` | ⚠️ zakucano `"sr-RS"` |
| 6 | Datum izrade | `05.08.2026` | numerički `dd.MM.yyyy` | ✅ ostaje — jednoznačan svuda |
| 7 | Ime i broj radnika, iznosi, sati | podatak | — | **ne menja se sa jezikom** |
| 8 | `RSD` | `summary.currencyCode` | — | ne prevodi se |
| 9 | Napomena radnika / direktora | TipTap HTML | — | **ne prevodi se** |

### Provereno: font ima ćirilicu

`payrollFonts.ts` nosi ugrađen Arial — 773 236 bajtova, `cmap` format 4, **2 792 kodne tačke**:

```
č ć š đ ž          OK    (sr-Latn)
А ж я ё            OK    (ru)
ђ ј љ њ ћ џ        OK    (sr-Cyrl)
```

**Zamena fonta nije potrebna.** Ovo je bio jedini nalaz koji je mogao da obori ceo pristup.

---

## 3. Ključna odluka: backend odlučuje jezik dokumenta, frontend ga prati

Naivna izvedba bi bila: korisnik izabere `ru` → frontend uzme `ru.json` labele **i** pozove `details?locale=ru`. Ako se ta dva ikada raziđu (backend ne zna `ru`, pa tiho vrati srpski), dobija se **obračun na dva jezika**.

Zato:

```
1. korisnik klikne "Русский"
2. GET /payroll-run-items/by-monthly-report/{id}/details?locale=ru
3. odgovor nosi  resolvedLocale: "ru"     ← backend kaže šta je STVARNO upotrebio
4. frontend uzima labele po resolvedLocale, NE po onome što je korisnik kliknuo
```

Jedan izvor istine za jezik dokumenta: backendov odgovor. **Polovično preveden obračun postaje strukturno nemoguć**, a ne samo malo verovatan.

`resolvedLocale` je aditivno polje na postojećem `PayrollRunItemDetailResponse` — nijedan klijent se ne lomi.

---

## 4. Backend

### 4.1 Migracija — jedna, sedam redova

```sql
-- =============================================================================
-- Obračun se generiše na jeziku zaposlenog. Skup jezika je fiksan i mora se
-- poklapati sa AppLocales.SUPPORTED i sa isporučenim JSON resursima na
-- frontendu. Dodavanje jezika dira tačno ta tri mesta, u istom PR-u.
-- Re-runnable.
-- =============================================================================
ALTER TABLE employees DROP CONSTRAINT IF EXISTS chk_employees_preferred_locale;

ALTER TABLE employees
    ADD CONSTRAINT chk_employees_preferred_locale
    CHECK (preferred_locale IN ('sr-Latn', 'sr-Cyrl', 'en', 'ru'));

COMMENT ON COLUMN employees.preferred_locale IS
    'Jezik dokumenata koji se prave ZA ovog zaposlenog (obračunski PDF). Nezavisno
     od is_foreigner, državljanstva i načina obračuna. Nikada ne utiče ni na jedan
     izračunat iznos. Skup vrednosti mora se poklapati sa AppLocales.SUPPORTED
     i sa isporučenim JSON resursima na frontendu.';
```

Postojeće vrednosti su `'sr-Latn'` i `'en'` — obe ostaju važeće, pa nema nijednog `UPDATE`-a nad podacima.

**Ne dira se** `work_code_category_translations` ni `payroll_adjustment_category_translations` — njihov `locale VARCHAR(35)` već prima bilo koji tag, a `UNIQUE (entity_id, lower(locale))` već sprečava `EN`/`en` duplikat.

### 4.2 `AppLocales` — proširenje, ne prepravka

```java
public final class AppLocales {

    public static final String DEFAULT          = "sr-Latn";
    public static final String SERBIAN_CYRILLIC = "sr-Cyrl";
    public static final String ENGLISH          = "en";
    public static final String RUSSIAN          = "ru";

    /** Redosled je redosled u padajućoj listi — otuda LinkedHashSet, ne Set.of. */
    public static final Set<String> SUPPORTED = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(DEFAULT, SERBIAN_CYRILLIC, ENGLISH, RUSSIAN)));

    /** Read putanja: nikad izuzetak. Nepoznat jezik → DEFAULT. Već postojeće ponašanje. */
    public static String normalize(String requested) { … }   // §4.3

    /** Write putanja: case-insensitive provera. NOVO — zamenjuje SUPPORTED.contains(). */
    public static boolean isSupported(String code) {
        return code != null && SUPPORTED.stream().anyMatch(s -> s.equalsIgnoreCase(code.trim()));
    }
}
```

`DEFAULT` i `ENGLISH` ostaju sa istim imenima i vrednostima, pa `Employee.java:72`, `PayrollRunItemService:600`, `WorkCodeCategoryService:53` i `PayrollAdjustmentCategoryService:199` nastavljaju da rade nepromenjeni.

### 4.3 Normalizacija

Skup je fiksan, pa tabela sme biti eksplicitna — ali pravilo za `sr` i za region i dalje treba da bude izvedeno, ne prepisano.

```
normalize(zahtevani):
  1. null / prazno                        → DEFAULT
  2. trim, '_' → '-'
  3. tačno poklapanje (case-insensitive)  → kanonski oblik iz SUPPORTED
  4. odbaci region, probaj ponovo         → 'en-GB'→'en', 'ru-RU'→'ru', 'sr-Latn-RS'→'sr-Latn'
  5. samo jezik ostao:
       tačno jedan podržan kod s tim jezikom → taj
       više njih                             → DEFAULT ako je među njima
  6. inače                                → DEFAULT
```

| Ulaz | Rezultat | Korak |
|---|---|---|
| `sr-Latn`, `SR-LATN`, `sr_Latn` | `sr-Latn` | 3 |
| `sr-Latn-RS`, `sr-Cyrl-RS` | `sr-Latn`, `sr-Cyrl` | 4 |
| `en-US`, `en-GB` | `en` | 4 |
| `ru-RU` | `ru` | 4 |
| **`sr`** | **`sr-Latn`** | 5 — dva srpska koda, `DEFAULT` je među njima |
| `de`, `xx`, `?!` | `sr-Latn` | 6 |

**Odgovor na pitanje o `sr` iz prvog zahteva:** `sr` je alias za `sr-Latn`, ali to nije red u tabeli niti `if` u kodu — to je posledica pravila 5. Ako bi se podrazumevani ikada promenio na ćirilicu, `sr` bi ga pratio bez izmene ijedne linije.

### 4.4 Dva režima

| Putanja | Ponašanje | Zašto |
|---|---|---|
| **Čitanje** (`?locale=`, generisanje dokumenta) | `normalize()` — nikad greška | Obračun mora da se odštampa. Nepoznat jezik daje srpski, ne 500. |
| **Upis** (`preferred_locale`) | `isSupported()` → inače **400** sa listom | Tiho pretvaranje bi upisalo pogrešan jezik na karton radnika. |

### 4.5 Uopštavanje endpointa za prevode

Danas su oba **zakucana na engleski**:

- `PUT /api/work-code-categories/{id}/translations/en` → `service.setEnglishName(id, name)` koji piše `AppLocales.ENGLISH`
- `nameEn` polje na `PayrollAdjustmentCategoryCreateRequest` / `…Response` → `applyEnglishName()` koji piše `AppLocales.ENGLISH`

Za `ru` i `sr-Cyrl` to mora primiti proizvoljan jezik:

```java
// WordCodeCategoryController — novo, uz postojeće /en koje ostaje kao delegat
@PutMapping("/{id}/translations/{locale}")
public ResponseEntity<WorkCodeCategoryDto> setTranslation(
        @PathVariable Long id,
        @PathVariable String locale,
        @Valid @RequestBody UpdateWorkCodeCategoryTranslationRequest request) { … }
```

```java
// WorkCodeCategoryService
@Transactional
public WorkCodeCategoryDto setTranslation(Long categoryId, String locale, String name) {
    if (!AppLocales.isSupported(locale)) throw new IllegalArgumentException(
            "Nepodržan jezik: " + locale + ". Dozvoljeni su: " + String.join(", ", AppLocales.SUPPORTED) + ".");
    if (AppLocales.isDefault(locale)) throw new IllegalArgumentException(
            "Naziv na srpskom se menja na samoj kategoriji, ne kao prevod.");
    …  // ista logika kao setEnglishName: prazno briše red, inače upsert
}

/** @deprecated Koristi setTranslation(id, AppLocales.ENGLISH, name). */
@Deprecated
public WorkCodeCategoryDto setEnglishName(Long id, String nameEn) {
    return setTranslation(id, AppLocales.ENGLISH, nameEn);
}
```

Ista izmena za `PayrollAdjustmentCategoryService.applyEnglishName` → `applyTranslations(category, Map<String,String>)`, uz `nameEn` koje ostaje kao deprecated prečica.

**Odbijanje `sr-Latn` je namerno.** Srpski naziv živi na `work_code_categories.category_name` i `payroll_adjustment_categories.name` — jedno mesto, kako je već odlučeno i dokumentovano u `2026-07-27-04`. Dozvoliti `sr-Latn` prevod bi napravilo drugu kopiju koja se razilazi.

### 4.6 `resolvedLocale` u odgovoru

`PayrollRunItemService.getDetails(monthlyReportId, locale)` već razrešava jezik (`:469`, `:600-602`):

```
?locale=  →  employee.preferred_locale  →  AppLocales.DEFAULT
```

Dodaje se jedno polje na odgovor:

```java
public record PayrollRunItemDetailResponse(
        …,                       // sve postojeće, nepromenjeno
        String resolvedLocale    // NOVO — jezik koji je stvarno upotrebljen
) {}
```

### 4.7 Izveštaj o nedostajućim prevodima

```
GET /api/admin/translations/completeness
```

```json
{
  "locales": ["sr-Latn", "sr-Cyrl", "en", "ru"],
  "entities": [
    { "entity": "WORK_CODE_CATEGORY",        "total": 24, "translated": { "sr-Cyrl": 0, "en": 24, "ru": 0 },
      "missing": { "sr-Cyrl": ["J","D","G", "…"], "ru": ["J","D","G", "…"] } },
    { "entity": "PAYROLL_ADJUSTMENT_CATEGORY","total": 13, "translated": { "sr-Cyrl": 0, "en": 13, "ru": 0 },
      "missing": { … } }
  ]
}
```

Jedan `LEFT JOIN` po entitetu. Ovo je jedina „vidljivost nedostajućih prevoda" koja je potrebna, jer je JSON deo pokriven kompajlerom.

### 4.8 Šta se NE dira

| | |
|---|---|
| `user_preferences.language` | **Van obima.** Aplikacija ostaje na srpskom. Zapisati izričito da **nije** izvor jezika za obračun — to je prva greška koju će neko napraviti. |
| Backend poruke o greškama, notifikacije, e-mail | Ostaju na srpskom. Čita ih administrator. |
| Spring `MessageSource`, `Accept-Language`, `LocaleResolver` | Ne uvode se. Jezik dokumenta nema veze sa jezikom korisnika; header bi otvorio vrata da neko kasnije zameni jedno drugim. |
| Resolveri i `CategoryTranslationIT` | Nepromenjeni. |

---

## 5. Frontend

### 5.1 JSON resursi sa garancijom u vreme kompajliranja

```
src/ui/features/payrolls/i18n/
  sr-Latn.json      ← izvorni, definiše skup ključeva
  sr-Cyrl.json
  en.json
  ru.json
  index.ts
```

```ts
// index.ts
import srLatn from './sr-Latn.json';
import srCyrl from './sr-Cyrl.json';
import en     from './en.json';
import ru     from './ru.json';

/** sr-Latn je izvorni fajl — on definiše koje labele obračun uopšte ima. */
export type LabelKey = keyof typeof srLatn;

/**
 * Anotacija Record<LabelKey, string> je ovde CELA POENTA: ako `ru.json` ne
 * sadrži neki ključ iz `sr-Latn.json`, `tsc -b` pada. Jezik kojem fali labela
 * ne može da se isporuči.
 */
export const DOCUMENT_LABELS = {
  'sr-Latn': srLatn as Record<LabelKey, string>,
  'sr-Cyrl': srCyrl as Record<LabelKey, string>,
  'en':      en     as Record<LabelKey, string>,
  'ru':      ru     as Record<LabelKey, string>,
} as const;

export type LocaleCode = keyof typeof DOCUMENT_LABELS;
export const DEFAULT_LOCALE: LocaleCode = 'sr-Latn';

/** Padajuća lista se IZVODI iz resursa — jezik bez labela ne može da postoji. */
export const LOCALES: ReadonlyArray<{ code: LocaleCode; nativeName: string }> = [
  { code: 'sr-Latn', nativeName: 'Srpski (latinica)' },
  { code: 'sr-Cyrl', nativeName: 'Српски (ћирилица)' },
  { code: 'en',      nativeName: 'English'           },
  { code: 'ru',      nativeName: 'Русский'           },
];

export function labelsFor(locale: string): Record<LabelKey, string> {
  return DOCUMENT_LABELS[locale as LocaleCode] ?? DOCUMENT_LABELS[DEFAULT_LOCALE];
}
```

`nativeName` je ime jezika **na tom jeziku** i namerno se ne prevodi — „Русский" se piše isto na svakom ekranu.

**Ovo je najjači argument za JSON u ovom slučaju:** baza ne može da obori build zbog nedostajućeg prevoda, samo da tiho padne na srpski.

`sr-Latn.json` — ~30 ključeva, izvučenih iz `PayrollPdf.tsx`:

```json
{
  "header.employeeName":       "Ime i prezime:",
  "header.employeeNo":         "Broj:",
  "header.hourlyRate":         "Vrednost radnog sata:",
  "header.phoneCurrentMonth":  "Telefon za tekući mesec:",
  "header.issueDate":          "Datum izrade:",
  "header.period":             "Mesec:",
  "efficiency.monthlyTotal":   "Ukupna mesečna efikasnost",
  "charges.title":             "Pregled zaduženja",
  "charges.category":          "Kategorija",
  "charges.normHours":         "Sati u normi",
  "charges.coefficient":       "Koeficijent",
  "charges.achievedHours":     "Ostvareni sati",
  "charges.normHourUnit":      "Sat u normi",
  "charges.achievedHourUnit":  "Ostvareni sat",
  "charges.hourlyRateCol":     "Vred. r.s. ({{currency}})",
  "charges.amountCol":         "Zaduženje ({{currency}})",
  "charges.mealAllowance":     "Topli obrok",
  "charges.mealValueCol":      "Vrednost T.O. ({{currency}})",
  "charges.mealTotalCol":      "Ukupno ({{currency}})",
  "charges.dayCount":          "Broj dana",
  "charges.totalAchieved":     "Ukupan zbir ostvarenih sati:",
  "charges.totalHours":        "Ukupan broj sati:",
  "charges.adjustedHours":     "Ukupan broj uvećanih / umanjenih sati:",
  "settlements.title":         "Pregled razduženja",
  "settlements.item":          "Stavka",
  "settlements.note":          "Napomena",
  "settlements.amountCol":     "Razduženje ({{currency}})",
  "settlements.empty":         "Nema razduženja.",
  "totals.grossEarnings":      "Ukupna zarada:",
  "totals.paidPreviousPeriod": "Isplaćeno u prethodnom obračunskom periodu: (-)",
  "totals.previousBalance":    "Prethodno stanje:",
  "totals.balance":            "Saldo:",
  "totals.netPayable":         "SVEGA ZA ISPLATU:",
  "notes.director":            "Napomena Direktora:",
  "notes.employee":            "Napomena:"
}
```

`{{currency}}` je jedina interpolacija i rešava se jednom pomoćnom funkcijom od tri reda — **ne** uvodi se i18next zbog nje.

Pluralizacija ne postoji na obračunu (nema „3 radnika"), pa ni ICU nije potreban. Da zatreba, dodaje se tada.

### 5.2 Padajuća lista na dugmetu PDF

Danas je `EmployeePayroll.tsx:285-295` običan `Button`. Postaje `Menu`, sa dva ponašanja:

- **klik na samo dugme** → radnikov jezik, jedan klik kao i sada;
- **klik na strelicu** → lista svih jezika, radnikov prvi i obeležen.

```tsx
<Button.Group>
  <Button leftSection={<IconFileTypePdf size={14} />} loading={pdfLoading}
          onClick={() => handleDownloadPdf(employeeLocale)}>
    PDF
  </Button>
  <Menu position="bottom-end">
    <Menu.Target><ActionIcon aria-label="Jezik obračuna"><IconChevronDown size={14} /></ActionIcon></Menu.Target>
    <Menu.Dropdown>
      <Menu.Label>Jezik obračuna</Menu.Label>
      {orderedLocales.map(l => (
        <Menu.Item key={l.code} onClick={() => handleDownloadPdf(l.code)}
          rightSection={l.code === employeeLocale
            ? <Badge size="xs" variant="light">radnikov</Badge> : null}>
          {l.nativeName}
        </Menu.Item>
      ))}
    </Menu.Dropdown>
  </Menu>
</Button.Group>
```

`orderedLocales` = `LOCALES` sa radnikovim jezikom podignutim na vrh. `employeeLocale` dolazi iz `employee.preferredLocale`, koji `EmployeeInfo` već čita (`employees.types.ts:150`).

### 5.3 Tok generisanja

```ts
async function handleDownloadPdf(requested: LocaleCode) {
  const detail = await fetchPayrollRunItemDetail(monthlyReportId, requested);
  const locale = detail.resolvedLocale;          // ← backend odlučuje, ne korisnik
  await downloadPayrollPdf({ …, labels: labelsFor(locale), locale });
}
```

`fetchPayrollRunItemDetail` (`payrollRunItems.api.ts:85`) dobija drugi argument i dodaje `?locale=`. Danas ga ne šalje uopšte, pa je sve uvek srpski.

**Nema drugog mrežnog poziva** — labele su u bundle-u.

### 5.4 `PayrollPdf.tsx` — tri izmene

**(a) Labele iz props-a**

```tsx
type PayrollPdfProps = { …; labels: Record<LabelKey, string>; locale: string };

const L = (k: LabelKey, vars?: Record<string, string>) => {
  const s = labels[k];
  return vars ? s.replace(/\{\{(\w+)\}\}/g, (_, v) => vars[v] ?? '') : s;
};

<Text>{L('header.employeeName')}</Text>
<Text>{L('charges.amountCol', { currency: currencyCode })}</Text>
```

Bez `?? key` fallback-a — TypeScript već garantuje da ključ postoji.

**(b) Brojevi po jeziku dokumenta**

```tsx
const nf = new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const fmt = (v: number | null | undefined) => nf.format(v ?? 0);
```

Zamenjuje `value.toLocaleString("sr-RS", …)` na liniji 107.

```
sr-Latn / sr-Cyrl   1.234,50
en                  1,234.50
ru                  1 234,50
```

`Intl` prima `sr-Latn` i `sr-Cyrl` direktno — nije potrebna mapa. **Provera u Electron build-u je obavezna** (§8, T-3): zavisi od ICU verzije. Rizik je nizak jer nepoznat tag tiho pada na `sr` što daje isti rezultat, ali test to mora pokriti eksplicitno.

**(c) Naziv meseca**

`periodFormatted` se danas računa u `EmployeePayroll.tsx:242` iz zakucanog `MONTHS_SR` niza. Postaje:

```ts
const periodFormatted = summary.period
  ? new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' })
      .format(new Date(Number(y), Number(m) - 1, 1))
  : '—';
```

Računa se **unutar** `handleDownloadPdf`, sa jezikom dokumenta — ne jednom za ceo ekran. Ekran i dalje prikazuje srpski.

**Ne menja se:** `todayFormatted()` (`dd.MM.yyyy` je jednoznačan svuda), iznosi, koeficijenti, `HtmlNote`, font, raspored, boje.

### 5.5 Padajuća lista jezika na kartonu zaposlenog

`EmployeeInfo.tsx:778-791` danas ima dve zakucane opcije:

```tsx
data={[{ value: 'sr-Latn', label: 'Srpski' }, { value: 'en', label: 'Engleski' }]}
…
{employee.preferredLocale === 'en' ? 'Engleski' : 'Srpski'}
```

Postaje `LOCALES.map(l => ({ value: l.code, label: l.nativeName }))`, a prikaz `LOCALES.find(...)?.nativeName ?? employee.preferredLocale`. Isti izvor kao PDF lista.

### 5.6 Bez i18next

Nijedna nova npm zavisnost. Nema `sr.json`/`en.json` za UI, nema switcher-a, nema `I18nextProvider`. Ne dira se nijedan od ~700 UI stringova, nijedna od 145 test asercija na srpskom.

Jedini „prevodilac" na frontendu je funkcija `L()` unutar `PayrollPdf.tsx`.

---

## 6. Admin ekran za nazive iz baze

**Danas ne postoji nijedan ekran** za `work_code_categories` ni za `payroll_adjustment_categories` — proveren ceo `src/ui`, nema nijednog poziva ka tim admin endpointima. Backend ima `PUT …/translations/en` i `nameEn`, ali ih ništa ne zove.

Zato postoje dva puta do ruskih i ćiriličnih naziva:

| | Kada | Trošak |
|---|---|---|
| **(a) Migracija sa seed-om** | za prvo puštanje | jedan SQL fajl, nula frontend posla |
| **(b) Admin ekran** | kad nazivi počnu da se menjaju | nova stranica + uopšteni endpointi (§4.5) |

**Preporuka: (a) za prvo puštanje, (b) kao zasebna faza.** Nazivi kategorija se menjaju retko, a bez ekrana funkcija radi u celini već posle faze 3.

Ekran, kada dođe — jedna matrica, redovi su stavke, kolone su jezici:

| Stavka | Tip | sr-Latn | sr-Cyrl | en | ru |
|---|---|---|---|---|---|
| `MEAL_ALLOWANCE` | Stavka obračuna | Topli obrok | *(prazno)* | Meal allowance | *(prazno)* |
| `J` | Radna kategorija | I, II smena | *(prazno)* | 1st, 2nd shift | *(prazno)* |

- `sr-Latn` kolona piše u **master** kolonu (`category_name` / `name`), ne u translation tabelu; ne može se obrisati, samo izmeniti.
- Ostale kolone pišu u translation tabele; „Obriši prevod" vraća fallback.
- Prazno polje pokazuje fallback kao sivi placeholder — administrator vidi šta radnik trenutno vidi.
- Filter „nepotpuni" + brojač po jeziku, iz `/api/admin/translations/completeness`.
- Dozvola: nova `TRANSLATIONS_ADMIN` u postojećem `RolePermissions`, po obrascu `USER_PREFERENCES_ADMIN`.
- Audit: automatski, kroz `audit_trigger_fn()` koji obe translation tabele već imaju.
- **Izmena prevoda ne pokreće recalc** — prevod ne utiče ni na jedan iznos. Zapisati u dokumentaciju.

---

## 7. Održavanje skupa jezika

Dodavanje jezika dira **tačno tri mesta, u istom PR-u**:

```
1. AppLocales.SUPPORTED                                       (backend)
2. CHECK ograničenje na employees.preferred_locale            (migracija)
3. features/payrolls/i18n/{locale}.json + LOCALES lista       (frontend)
```

Zaštita od razilaženja:

| Rizik | Zaštita | Tip |
|---|---|---|
| JSON fali labelu | `Record<LabelKey, string>` → **build pada** | kompajler |
| `AppLocales` ≠ `CHECK` | IT koji upisuje svaki podržani jezik u `preferred_locale` i tvrdi da prolazi, plus jedan nepodržan koji pada | test |
| Frontend ima jezik koji backend nema | Upis `preferred_locale` vraća **400**; a i tada `resolvedLocale` (§3) sprečava polovičan dokument | runtime |
| Backend ima jezik koji frontend nema | `labelsFor()` pada na `sr-Latn`; dokument je ceo na srpskom, ne polovičan | runtime |

Dva su repozitorijuma bez zajedničkog build-a, pa je poslednje dve stavke **proces plus runtime zaštita, ne automatika**. Zato `resolvedLocale` iz §3 nije kozmetika — on je ono što oba smera razilaženja pretvara u „ceo dokument na srpskom" umesto u „pola-pola".

Kontrolna lista ide u `docs/business-rules/i18n-obracun.md`.

---

## 8. Testovi

### Baza

| Test |
|---|
| `preferred_locale` prihvata sva 4 podržana jezika |
| `preferred_locale = 'sr'` odbijen — dvosmislen tag ne sme u bazu |
| `preferred_locale = 'de'` odbijen |
| Postojeći redovi (`sr-Latn`, `en`) prežive migraciju bez `UPDATE`-a |
| Migracija primenjena dvaput bez greške |

### Backend

| Test |
|---|
| `normalize()` — cela tabela §4.3, uključujući `sr` → `sr-Latn` |
| `normalize()` nepoznatog i praznog → `DEFAULT`, nikad izuzetak |
| `isSupported()` case-insensitive; `isSupported("de")` = false |
| `setTranslation(id, "sr-Latn", …)` odbijen — srpski se menja na master redu |
| `setTranslation(id, "de", …)` → 400 |
| Prazan naziv briše red, ne upisuje prazan string (postojeće ponašanje) |
| `getDetails` vraća `resolvedLocale` = radnikov jezik kad `?locale=` nije poslat |
| `getDetails(?locale=de)` → `resolvedLocale: "sr-Latn"` |
| `completeness` tačno broji nedostajuće po jeziku |
| **`PayrollGoldenSnapshotIT` parametrizovan po jeziku — iznosi bit-identični** |
| `CategoryTranslationIT` — 9 postojećih, **nepromenjeni** |

### Frontend

| Test |
|---|
| **`tsc -b` pada kad se ključ ukloni iz `ru.json`** — dokazuje garanciju iz §5.1 |
| Svi JSON fajlovi imaju identičan skup ključeva (hvata i višak, koji tip ne hvata) |
| Meni prikazuje sve jezike, radnikov prvi i obeležen |
| Klik na samo dugme preuzima na radnikovom jeziku |
| **Labele se biraju po `resolvedLocale`, ne po kliknutom jeziku** |
| `PayrollPdf` renderuje ćirilični tekst (`ru` i `sr-Cyrl`) |
| `Intl.NumberFormat` po jeziku: `1.234,50` / `1,234.50` / `1 234,50` |
| Naziv meseca po jeziku dokumenta, dok ekran ostaje srpski |
| Iznosi identični u sva četiri jezika |

### T-3 — provera u Electron build-u

`Intl.NumberFormat('sr-Latn')` i `Intl.DateTimeFormat('sr-Cyrl', {month:'long'})` moraju dati očekivan izlaz u `npm run dist:mac`, ne samo u `vitest` pod Node-om. ICU podskup se razlikuje.

---

## 9. Faze

| Faza | Sadržaj | Backend | Frontend | Migracija |
|---|---|---|---|---|
| **1** | `AppLocales` na 4 jezika, `isSupported()`, `CHECK`, `resolvedLocale` | 1 izmenjena klasa, 1 DTO polje, 2 popravke | — | ✅ 1 fajl |
| **2** | JSON resursi + prevod ~30 labela na sr-Cyrl / en / ru | — | 4 JSON + `index.ts` | — |
| **3** | PDF: lista jezika, labele, `Intl`, `?locale=` | — | `EmployeePayroll.tsx`, `PayrollPdf.tsx`, `payrollRunItems.api.ts`, `EmployeeInfo.tsx` | — |
| **4** | Nazivi kategorija na ru / sr-Cyrl — seed migracijom | — | — | ✅ 1 fajl |
| **5** *(opciono)* | Uopšteni endpointi + admin matrica + `completeness` | 3 klase, 1 dozvola | 1 stranica | — |

**Posle faze 4 funkcija radi u celini.** Faza 5 je samo prelazak sa „programer menja nazive migracijom" na „administrator ih menja u aplikaciji".

### Obim

| | Ceo UI | Admin dodaje jezik | **Ovo** |
|---|---|---|---|
| Migracija | 8–10 | 3 | **1** (+1 seed) |
| Nove tabele | 4–6 | 3 | **0** |
| Novih Java klasa | ~15 | ~10 | **0** (faza 1–4) / ~3 (faza 5) |
| Nove npm zavisnosti | 3 | 0 | **0** |
| Frontend fajlova | ~470 | ~6 | **~6** |
| UI stringova za prevod | ~700 | 0 | **0** |
| Test asercija koje pucaju | 145+ | 0 | **0** |
| Stringova za prevod po jeziku | ~2 800 | ~47 | **~70** (30 labela + ~40 naziva) |

---

## 10. Granice — šta ovo namerno ne pokriva

| | |
|---|---|
| **UI aplikacije** | Ostaje `sr-Latn`. Ako se predomisliš, plan iz prvog dokumenta ostaje važeći i ovo rešenje ga **ne blokira** — `AppLocales` i `preferred_locale` bi mu služili nepromenjeni. |
| **Nov jezik bez novog build-a** | Nije moguće. To je svesna cena, i vraćanje je kasnije mala migracija: 30 labela iz JSON-a u tabelu. |
| **`ManufacturingTimePdf`** | Nije dirnut. Obrazac je isti — `features/manufacturing-times/i18n/{locale}.json` — kada zatreba. |
| **Backend greške, notifikacije, e-mail** | Ostaju na srpskom. Čita ih administrator. |
| **Slobodan tekst** (napomene, razlozi korekcija) | **Nikad se ne prevodi.** Ostaje na jeziku unosa. |
| **Automatska transliteracija latinica → ćirilica** | Ne radi se. Nazivi sadrže kodove (`PL`, `L3`), strane termine („Galvanizacija") i `RSD`. Ako se poželi pomoć, generiše se **predlog** za ljudski pregled, nikad direktan upis. |

---

## 11. Otvorene odluke

| # | Pitanje | Preporuka |
|---|---|---|
| **O-1** | Prate li iznosi na PDF-u jezik dokumenta? | **Da.** `sr` (`1.234,50`) i `en` (`1,234.50`) se stvarno mogu pogrešno pročitati. |
| **O-2** | Treba li `sr-Cyrl` uopšte? | **Uključiti.** Košta jedan JSON fajl; ako se ne koristi, ne pojavljuje se u praksi. Kasnije dodavanje je isti posao kao sada. |
| **O-3** | Ko prevodi ~30 labela na ruski? | Blokira fazu 2. Do tada `ru.json` ne može ni da postoji — build bi pao. **Ako prevodioca nema, `ru` se ne dodaje** i funkcija se pušta sa `sr-Latn`/`sr-Cyrl`/`en`. |
| **O-4** | Nazivi kategorija: seed migracijom ili admin ekran? | **Seed za prvo puštanje** (faza 4), ekran kasnije (faza 5). |
| **O-5** | Da li `sr-Cyrl` treba i za `employees.preferred_locale`, ili je ćirilica samo opcija u padajućoj listi PDF-a? | **Oba** — cena je nula, a radnik koji čita ćirilicu dobija svoj podrazumevani jezik. |

---

## 12. Uraditi odmah, nezavisno od svega

1. **`EmployeeService:345`** — `AppLocales.SUPPORTED.contains()` je case-sensitive, dok `AppLocales.normalize()` radi `equalsIgnoreCase`. Dva pojma „podržanog jezika" u istoj aplikaciji. Prelazi na `isSupported()`.
2. **Zapisati da `user_preferences.language` NIJE izvor jezika za obračun.** Polje postoji, nevalidirano je (`UserPreferencesService:61` samo `trim()`), a podrazumevana vrednost mu je `'sr'`. Prva greška koju će neko napraviti je da ga zameni sa `employees.preferred_locale`.
3. **Provera `Intl` u Electron build-u** (T-3) pre zaključenja faze 3.
