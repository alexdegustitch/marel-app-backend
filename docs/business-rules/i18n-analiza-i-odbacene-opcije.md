# Višejezičnost — analiza zatečenog stanja i odbačene opcije

> **Status: ANALIZA JE VAŽEĆA, PLAN NIJE.**
>
> | Deo | Status |
> |---|---|
> | **§1–§3** — šta već postoji, inventar hardkodovanih tekstova, inventar prevodivih DB tabela | ✅ **važeće**, koristi kao referencu |
> | **§4–§24** — plan pune i18n aplikacije (i18next, ~700 UI stringova, admin-dodavanje jezika) | ❌ **NIJE IZABRAN** |
>
> Odluka je u [`i18n-obracun.md`](i18n-obracun.md): prevodi se **samo obračunski
> PDF**, interfejs ostaje na srpskoj latinici, skup jezika je fiksan.
>
> **Zašto je ovaj dokument sačuvan.** Tri stvari u njemu bi se skupo ponovo
> otkrivale:
> 1. **§1** — tačan popis i18n infrastrukture koja **već postoji** u projektu, sa
>    fajlovima i migracijama.
> 2. **§2–§3** — izmereni inventar: ~691 različit UI string u 470 fajlova, 196
>    srpskih literala na backendu, i tabela svih DB entiteta sa ocenom da li im
>    prevod ima smisla ili nema. **Ovo je odgovor na „šta bi sve trebalo prevesti"**
>    i važi bez obzira na izabrani pristup.
> 3. **§8, §21** — poređenje četiri modela skladištenja prevoda i popis rizika.
>
> **Kada bi §4–§24 postali relevantni.** Ako se ikada odluči da i korisnički
> interfejs mora biti višejezičan. Rešenje iz `i18n-obracun.md` to **ne blokira** —
> `AppLocales` i `employees.preferred_locale` bi mu služili nepromenjeni.
>
> Ne implementiraj ništa iz §4–§24 bez izričite odluke da se obim proširuje.

---


**Status:** analiza i plan. Ništa nije implementirano, nijedan postojeći fajl nije izmenjen.
**Datum analize:** 2026-08-05
**Analizirano:** `marel-app-backend-server` @ `6fddfb5`, `marel-app` @ `f4c534b` (grana `feature/employee-compensation-schemes-i18n`)

---

## 0. Najvažniji nalaz pre svega ostalog

**Ovaj projekat već ima radnu, promišljenu i testiranu i18n infrastrukturu za bazu podataka.** Ona je uvedena migracijama `2026-07-27-04`, `-05` i `-07`, dokumentovana je u
`docs/business-rules/compensation-schemes-and-category-localization.md` §11, i pokrivena je sa 9 testova u `CategoryTranslationIT`.

Arhitektonski pravac koji je zahtev tražio — po-entitetska translation tabela, stabilan `code`, centralizovan fallback, `displayName` razrešen na backendu — **nije novi predlog za ovaj projekat, to je opis onoga što već postoji.** Postojeći model je:

> **Napomena o tonu.** Ovaj dokument je nastao kao odgovor na konkretan zahtev i na
> više mesta se obraća njegovom autoru u drugom licu („tvoj zahtev", „tvoja opcija 3").
> Sadržaj je time nepromenjen; čitaj to kao referencu na polazni zahtev, ne kao
> obraćanje sebi.

Postojeći model je:

```
work_code_categories            →  work_code_category_translations
payroll_adjustment_categories   →  payroll_adjustment_category_translations
```

Zato ovaj plan **nije "uvedi i18n"**, nego:

1. **proširi** postojeći obrazac na `sr-Cyrl` i `ru` (danas su podržani samo `sr-Latn` i `en`);
2. **ponovi** postojeći obrazac na 4 nova šifrarnika koji ga još nemaju;
3. **poveži** dormantnu backend lokalizaciju sa frontendom (danas frontend **nikada** ne šalje locale, pa se sve uvek razrešava na `sr-Latn`);
4. **uvede** ono čega uopšte nema — frontend i18n biblioteku i JSON resurse (~700 stringova);
5. **popravi** tri konkretne nekonzistentnosti koje su već u kodu (vidi §1.4).

Najveći deo posla nije baza. **Najveći deo posla je frontend.**

---

## 1. Sažetak trenutnog stanja

### 1.1 Šta već postoji na backendu

| Komponenta | Fajl | Šta radi |
|---|---|---|
| Registar lokala | `common/i18n/AppLocales.java` | `DEFAULT = "sr-Latn"`, `ENGLISH = "en"`, `SUPPORTED = {sr-Latn, en}`, `normalize()` (case-insensitive, fallback na DEFAULT), `isDefault()` |
| Translation tabela 1 | migracija `2026-07-27-04` | `work_code_category_translations` |
| Translation tabela 2 | migracija `2026-07-27-05` | `payroll_adjustment_category_translations` |
| Locale zaposlenog | migracija `2026-07-27-07` | `employees.preferred_locale VARCHAR(35) NOT NULL DEFAULT 'sr-Latn'`, `CHECK IN ('sr-Latn','en')` |
| Resolver 1 | `work_code/WorkCodeCategoryNameResolver.java` | `translationsFor(locale) → Map<Long,String>`, `displayName(id, defaultName, map)` |
| Resolver 2 | `payroll_adjustment_category/PayrollAdjustmentCategoryNameResolver.java` | isti ugovor |
| Testovi | `CategoryTranslationIT.java` | 9 testova: prevod, fallback, normalizacija, `EN`≡`en`, duplikat odbijen, prazan locale odbijen, prazan naziv odbijen, `code` se ne prevodi, transakcione tabele nemaju kolonu s prevodom |

Oblik obe postojeće translation tabele je **identičan** i treba ga tretirati kao kanonski šablon:

```sql
id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
<entity>_id            BIGINT NOT NULL   -- FK ... ON DELETE CASCADE
locale                 VARCHAR(35) NOT NULL
name                   VARCHAR(255) NOT NULL
created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at             TIMESTAMPTZ

CHECK (length(trim(locale)) > 0)
CHECK (length(trim(name)) > 0)
UNIQUE INDEX (<entity>_id, lower(locale))
INDEX (lower(locale))
TRIGGER set_updated_at()
TRIGGER audit_trigger_fn()  + red u audit_tables
```

Fallback je `COALESCE(translation.name, master.name)`. **`sr-Latn` se namerno ne seed-uje** — služi se iz master kolone (`work_code_categories.category_name`, `payroll_adjustment_categories.name`), pa postoji tačno jedno mesto za izmenu srpskog naziva. Eksplicitan `sr-Latn` red je i dalje legalan kao override.

### 1.2 Postojeći API sa locale-om

| Metod | Putanja | Locale |
|---|---|---|
| `GET` | `/api/payroll-run-items/by-monthly-report/{id}/details` | `?locale=` opciono; inače `employee.preferred_locale`; inače `AppLocales.DEFAULT` |
| `GET` | `/api/work-code-categories/active-work-code-categories` | `?locale=` opciono → `displayName` + `nameEn` |
| `GET` | `/api/employees/{id}/allowed-work-code-categories` | `?locale=` opciono → već lokalizovan `categoryName` |
| `PUT` | `/api/work-code-categories/{id}/translations/en` | postavljanje/brisanje engleskog naziva |
| `POST/PUT` | `/api/payroll-adjustment-categories` | polje `nameEn` na create/update, `nameEn` u response |

To je već **Opcija C** iz tvog zahteva §9: standardni read endpoint vraća razrešeni `displayName`, a administrativni put nosi `nameEn` posebno, da bi ekran mogao da razlikuje „nije prevedeno" od „prevedeno".

### 1.3 Čega nema

- **Nema Spring `MessageSource`, `LocaleResolver`, `messages*.properties`, ni jednog `Accept-Language` u celom backendu.** (`DefaultMessageSourceResolvable` u `GlobalExceptionHandler` je samo izvlačenje `@NotNull(message="...")` teksta, nije i18n.)
- **Nema nijedne i18n biblioteke na frontendu.** `package.json` nema `i18next`, `react-i18next`, `react-intl`, `lingui` — ništa.
- **Frontend nikada ne šalje locale.** Provereno na svim pozivnim mestima:
  - `fetchPayrollRunItemDetail()` (`payrollRunItems.api.ts:85`) ne dodaje `?locale=`;
  - `useAllowedWorkCodeCategories(employeeId, workDate)` prima treći `locale` argument, ali **jedini pozivalac** (`EmployeeShiftCreateMultipleLogsModal.tsx:75`) ga ne prosleđuje;
  - `fetchWorkCodeCategoriesOptions()` ne prosleđuje locale.

  **Posledica: cela backend lokalizacija je danas mrtav kod. Svaki odgovor se razrešava na `sr-Latn`.** Frontend ipak čita `categoryDisplayName ?? categoryName` (`PayrollAdjustmentsSection.tsx:80`, `PayrollPdf.tsx:408,451`, `PayrollCategoriesTable.tsx:317`, `EmployeePayroll.tsx:151`), pa je potrošačka strana spremna — nedostaje samo slanje locale-a.

### 1.4 Tri konkretne nekonzistentnosti koje već postoje u kodu

Ovo nisu hipotetički rizici — proverio sam ih u kodu.

**(A) `user_preferences.language` je `'sr'`, a `AppLocales.DEFAULT` je `'sr-Latn'`.**
Migracija `2026-07-21-08` definiše `language VARCHAR(10) NOT NULL DEFAULT 'sr'` **bez ijednog `CHECK` ograničenja**, a `UserPreferencesService:61-62` samo radi `trim()` i upisuje — **nikakve validacije**. Klijent može upisati `"klingon"` i proći. Tačno onaj dvosmisleni tag oko kojeg pitaš (`sr`) **već je u bazi kao podrazumevana vrednost**, i nije povezan sa `AppLocales` ni na koji način. Ovo je najhitnija stavka za popravku.

**(B) Validacija locale-a je case-sensitive na jednom mestu, case-insensitive na drugom.**
`EmployeeService:345` radi `AppLocales.SUPPORTED.contains(req.getPreferredLocale())` — **case-sensitive**, pa `"SR-LATN"` biva odbijeno.
`AppLocales.normalize()` poredi sa `equalsIgnoreCase` — pa `"SR-LATN"` biva prihvaćeno i normalizovano.
Dva različita pojma „podržan locale" u istoj aplikaciji.

**(C) `employees.preferred_locale` ima hardkodovan `CHECK IN ('sr-Latn','en')`.**
Dodavanje `sr-Cyrl` i `ru` **zahteva migraciju baze**, ne samo izmenu `AppLocales.SUPPORTED`. Ovo je zapravo dobro dizajnirano (baza ne dozvoljava smeće), ali mora ući u plan kao obavezan korak.

### 1.5 Ključno svojstvo za DB deo plana: audit tabelama diktira oblik ključa

`audit_trigger_fn()` upisuje `record_id` iz `NEW.id`:

```sql
INSERT INTO audit_logs(user_id, table_id, action_id, record_id, changes)
VALUES (..., NEW.id, v_changes);
```

**Zato svaka translation tabela koja treba da bude auditovana MORA imati surogat `id`.** Kompozitni PK `(entity_id, locale)` bez `id` kolone ne može da prođe kroz postojeći audit trigger. To definitivno rešava tvoje pitanje iz §4 („da li translation tabele treba da imaju sopstveni `id` ili je dovoljan složeni PK") — i objašnjava zašto obe postojeće tabele imaju `id IDENTITY PK` **plus** `UNIQUE (entity_id, lower(locale))`. Zadržavamo taj obrazac.

### 1.6 Migracioni sistem

Nema Flyway/Liquibase. Migracije su ručni SQL fajlovi u `src/main/resources/sql/`, imenovani `YYYY-MM-DD-NN-opis.sql`, primenjuju se `psql`-om po abecednom redosledu imena — **numerički prefiks je nosiv, ne kozmetika.**

Integracioni testovi (`AbstractIntegrationTest`) grade šemu na pravom PostgreSQL 18 kontejneru: baseline snapshot od 2026-07-21 + **svaka migracija po redosledu imena**, kroz `psql` sa `ON_ERROR_STOP=1`. Test profil koristi `ddl-auto=validate`.

**Posledica za nas: svaka nova migracija koju napišemo automatski ulazi u IT suite i mora biti idempotentna, jer se u dev-u primenjuje ručno i može biti pokrenuta dvaput.**

---

## 2. Inventar postojećih jezika i hardkodovanih tekstova

### 2.1 Jezici danas

| Gde | Vrednost | Validirano? |
|---|---|---|
| `AppLocales.SUPPORTED` | `sr-Latn`, `en` | — |
| `employees.preferred_locale` | `sr-Latn` \| `en` | ✅ DB `CHECK` + servis (case-sensitive) |
| `user_preferences.language` | default `'sr'` | ❌ ništa |
| `user_preferences.number_format` | default `'sr-RS'` | ❌ ništa, i **nigde se ne čita** |
| Frontend | hardkodovano | — |

### 2.2 Hardkodovani tekstovi — backend

**196 string literala sa srpskim dijakriticima u 44 fajla.** Realan broj korisničkih poruka je veći (mnoge srpske reči nemaju dijakritike).

Podela po nameni:

| Kategorija | Primeri | Gde |
|---|---|---|
| Domenske greške (409/400) | „nema način obračuna za period", „Obračunski mesec ne sme imati dva načina obračuna." | `WorkCategoryResolutionService`, `EmployeeCompensationSchemeService`, `PayrollAdjustmentService`, `PayrollRunItemService`, `PayrollConfigurationValidationService` |
| Generičke greške | „Neispravan format zahteva.", „Nemate ovlašćenje za ovu akciju.", „Neko drugi je u međuvremenu izmenio ovaj zapis…" | `GlobalExceptionHandler` (+ jedna engleska: „Something went wrong. Please try again.") |
| Bean-validation poruke | „Email adresa je predugačka", „Datum početka primene je obavezan" | `dto/*Request.java` (`@NotNull(message=…)`) |
| **Notifikacije koje se PERZISTUJU** | „Nova registracija čeka odobrenje", „Zahtev za proizvod X je završen." | `NotificationFanoutService:214-248` |
| **Nazivi praznika koji se PERZISTUJU** | „Nova godina", „Božić", „Dan državnosti Srbije", „Zamena za praznik (X)" | `SerbianHolidayCalculator` → `work_calendar_days.label` |
| Seed nazivi u migracijama | „Satnica", „Fiksni lični dohodak", „Ručna korekcija vremena", „Standardni obračun" | `2026-08-01-01`, `2026-08-27-01`, `2026-07-27-01` |

Dve poslednje kategorije su posebne i obrađene su u §3.4 i §3.5 — to je backend-generisan tekst koji **završava u bazi**, što je drugačiji problem od poruke koja se vrati u odgovoru.

**Pozitivan nalaz:** `grep` za `getName().equals(`, `getCategoryName().equals(`, `getLabel().equals(` po celom `src/main/java` — **nula pogodaka**. Nijedno poslovno pravilo na backendu se ne oslanja na prikazni naziv. Rezolucija ide preko `code` / `category_no` / id-a. To je već dokumentovano kao svesna odluka (`compensation-schemes-and-category-localization.md` §7: „Never key a lookup on a category code" — čak i kod, a kamoli naziv).

### 2.3 Hardkodovani tekstovi — frontend

Merenje (skripta nad `src/**/*.{ts,tsx}`, atributi `label|placeholder|title|description|header|message|aria-label|…` + JSX tekstualni čvorovi, isključeni CSS/id literali):

| Skup | Fajlova | Pojavljivanja | Različitih stringova |
|---|---|---|---|
| **Produkcioni kod** (bez `reference-v4` i testova) | 470 | **1 125** | **691** |
| `reference-v4` (dev-only galerija, ne ulazi u prod build) | 36 | 679 | 533 |
| Test fajlovi | 70 | 200 | 137 |

Ovo je donja granica. Realna procena ključeva za prevod: **1 300 – 1 800**, jer merenje ne hvata inline konkatenacije, poruke građene u `notification.service` pozivima i tekst u template stringovima.

Dodatno: **181 fajl** sadrži srpski dijakritik; **927** string literala sa dijakriticima.

Gustina po modulu (produkcioni kod):

| Modul | Fajlova | LOC | Fajlova sa srpskim tekstom |
|---|---|---|---|
| `features/shifts` | 83 | 10 984 | **41** |
| `features/employees` | 35 | 4 366 | 10 |
| `features/payrolls` | 27 | 3 633 | 10 |
| `components/*` | 37 | 2 466 | 20 |
| `features/analytics` | 27 | 1 761 | 12 |
| `features/production-orders` | 18 | 2 332 | 5 |
| `features/operations` | 21 | 1 650 | 8 |
| `features/records` | 28 | 1 192 | 7 |
| `features/manufacturing-times` | 11 | 1 856 | 2 |
| `features/payroll-records` | 20 | 869 | 7 |
| `features/bonuses` | 7 | 1 093 | 3 |
| `features/products` | 13 | 1 010 | 2 |
| `features/work-calendar` | 14 | 664 | 6 |
| `features/employees-calendars` | 10 | 596 | 2 |
| `features/settings` | 7 | 516 | 2 |
| `features/common` | 16 | 500 | 3 |
| `pages/*` | 29 | 246 | 1 |
| ostalo (`sample-orders`, `requests`, `users`) | 6 | 36 | 2 |

Formatiranje — hardkodovani locale literali:

| Literal | Pojavljivanja |
|---|---|
| `"sr-RS"` / `'sr-RS'` | 32 |
| `"sr-Latn-RS"` / `'sr-Latn-RS'` | 11 |
| `"sr"` (Mantine `DatePicker locale=`, `dayjs/locale/sr`) | 8 |
| `'sr-Latn'` (vrednost `preferredLocale`) | 6 |
| `'en'` | 3 |
| `"en-US"` | 1 |

Plus ručna lista meseca u `lib/utils/dateUtils.ts:1-14` (`monthNames`) i još jedna u `features/work-calendar/ui/WorkCalendarDayModal.tsx:15-16`, ručna lista dana u nedelji u `EmployeeCalendarMonthView.tsx:30` (`["Pon","Uto",…]`), i **12 PNG slika sa srpskim imenima meseci** u `src/ui/assets/months/` (`januar.png` … `decembar.png`) — te slike verovatno nose i utisnut tekst; treba proveriti pre lokalizacije eksporta.

Mape kod → labela (već „polu-spremne", jer ključ je stabilan kod):

| Fajl | Mapa |
|---|---|
| `features/production-orders/ui/productionOrderStatusMeta.ts` | `STATUS_LABELS`, `PRIORITY_FLAG_LABELS` |
| `features/payroll-records/mappers/payrollStatusDisplay.ts` | `DRAFT→Nacrt`, `APPROVED→Odobren`, `LOCKED→Zaključan` |
| `features/employees/ui/EmployeeInfo.tsx:1137-1140` | ista mapa, **duplirana** |
| `features/work-calendar/ui/workCalendarDayTypeMeta.ts` | `DAY_TYPE_LABELS` |
| `features/components/google-auth/completeGoogleAuth.ts` | `GOOGLE_AUTH_ERROR_MESSAGES` |
| `features/payrolls/domain/adjustmentPolicy.ts:125` | `ZERO_REASONS` |
| `features/employees/domain/payrollValueLabel.ts:13` | `'Da'` / `'Ne'` |

Ove mape su najlakše za migraciju — to su već `Record<StableCode, string>`, pa se pretvaraju u `t('...' + code)` bez promene logike.

**Pozitivan nalaz:** `grep` za `(categoryName|name|label|title) === "…"` u produkcionom frontend kodu — **nula pogodaka** koji su poslovna logika (5 pogodaka su `typeof title === "string"` provere tipa). Poređenja po stabilnom kodu: 9. **Frontend nijednom ne koristi prikazni naziv kao identifikator.**

Ruta i naslovi stranica su danas **mešavina srpskog i engleskog** (`Router.tsx`): „Login", „Employees", „Products", „Records", „Monthly Records" naspram „Vreme izrade proizvoda", „Obračun", „Zaposleni", „Kalendar radnika", „Pravila bonusa". Sve ide u `document.title` — treba unifikovati kroz i18n.

### 2.4 Testovi koji će pući

- **Frontend:** 70 test fajlova; **35** sadrži srpski dijakritik; **145** `getByText`/`findByText`/`getByRole`/`getByLabelText`/`toHaveTextContent` asercija sa dijakriticima (realno više). Ovo je najveći skriveni trošak faze 7.
- **Backend:** `CategoryTranslationIT` asertira srpske nazive („Zavarivanje", „Galvanizacija") — ali kao *podatke*, ne kao UI tekst; ti testovi ostaju validni.

---

## 3. Inventar DB tabela sa potencijalno prevodivim poljima

Pregledao sam svih 72 `@Entity` klase i sve migracije. Rezultat po kategorijama.

### 3.1 Već imaju translation tabelu — treba samo dodati locale

| Entitet | Tabela | Prevodiva polja | Stabilni kod | Status |
|---|---|---|---|---|
| `WorkCodeCategory` | `work_code_categories` | `category_name` | `category_no` | ✅ `work_code_category_translations` |
| `PayrollAdjustmentCategory` | `payroll_adjustment_categories` | `name` | `code` | ✅ `payroll_adjustment_category_translations` |

`work_code_categories.note` je **interna administrativna beleška**, ne prikazni naziv — **ne prevoditi**.

### 3.2 Preporučeno za novu translation tabelu

| # | Entitet | Tabela | Prevodiva polja | Stabilni kod | Zašto | Prioritet |
|---|---|---|---|---|---|---|
| 1 | `CompensationScheme` | `compensation_schemes` | `name` | `code` ✅ | „Standardni obračun" / „Fiksni koeficijent" prikazuju se na kartonu zaposlenog (`CompensationSchemeSection.tsx`) i u dropdown-u za dodelu | **Visok** |
| 2 | `PayrollTimeAdjustmentCategory` | `payroll_time_adjustment_categories` | `name`, `description` | `code` ✅ | ima `visible_in_ui` i `visible_in_pdf` flagove — po definiciji je prikazna; „Ručna korekcija vremena" ide na obračunski list | **Visok** |
| 3 | `EmployeePayrollValueDefinition` | `employee_payroll_value_definitions` | `name` | `code` ✅ | „Satnica", „Fiksni lični dohodak", „Fiksna mesečna nadoknada za prevoz" — prikazuju se u `PayrollValuesSection.tsx` | **Visok** |
| 4 | `AppSetting` | `app_settings` | `display_text`, `unit` | `setting_key` ✅ | `display_text` je **jedina** labela na ekranu Parametri (`AppSettingCard.tsx:122`) | Srednji |
| 5 | `BonusCategory` | `bonus_categories` | `category_name` | `category_no` ✅ | prikazuje se u `EmployeeInfo.tsx:926` i pravilima bonusa | Srednji |
| 6 | `Shift` | `shifts` | `name` | `shift_code` ✅ | nazivi smena („I smena"…) | Nizak |

`employee_payroll_value_definitions.description` i `payroll_time_adjustment_categories.description` su danas **na engleskom i pisani za programera** („Prices `payroll_run_item_categories`. A calculation input, not a payslip line…"). To nisu korisnički opisi. **Preporuka: ne prevoditi ih; premestiti u `COMMENT ON COLUMN` ili u novo polje `help_text` ako korisnički opis ikada zatreba.** Ovo je otvorena odluka D-7.

`app_settings.description` je isto interno — ne prevoditi. `app_settings.unit` (`RSD`, `min`, `%`) je granični slučaj: `RSD` i `%` su univerzalni, `min` nije. Preporuka: prevoditi `unit` kroz **frontend JSON** po stabilnoj vrednosti, ne kroz DB tabelu (§3.6).

### 3.3 Ima tekst, ali NEMA stabilan kod — zahteva odluku

| Entitet | Tabela | Problem |
|---|---|---|
| `Department` | `departments` | `name`, `description`, ali **nema `code` kolonu**. Ili se prvo doda `code VARCHAR NOT NULL UNIQUE`, ili se za ovaj entitet primeni Varijanta A (naziv ostaje u glavnoj tabeli kao fallback). |

**Preporuka:** odeljenja su malobrojna i administratorski uređivana; dodavanje `code` kolone je jeftinije i konzistentnije od izuzetka. Ali to je promena šeme koja nije deo i18n-a per se → **odluka D-5**.

### 3.4 Backend generiše i PERZISTUJE srpski tekst — poseban slučaj

Ovo su dva mesta gde translation tabela **nije** pravo rešenje.

**(a) `work_calendar_days.label`**
`SerbianHolidayCalculator` računa praznike i upisuje srpski naziv u `label`. Prevođenje već upisanog stringa je pogrešan pravac: tekst se generiše, nije unesen.

**Preporuka:** dodati `holiday_key VARCHAR(60)` na `work_calendar_days` (npr. `NEW_YEAR`, `CHRISTMAS`, `STATEHOOD_DAY`, `SUBSTITUTE_FOR:<key>`), zadržati `label` kao nepromenjeni fallback za ručno unesene i istorijske dane, a prevod raditi kroz **frontend JSON** po `holiday_key`. Praznici su fiksan, mali, poznat skup — ne treba im administrativni ekran, a `holiday_key` čini kalendar auditabilnim na način na koji tekst nije.

**(b) `notification_events.title` i `notification_events.message`**
`NotificationFanoutService:214-248` gradi srpski tekst konkatenacijom i **upisuje ga u bazu** (`title VARCHAR(200) NOT NULL`, `message VARCHAR(2000) NOT NULL`). Za višejezičnost je to najgori mogući oblik: tekst je zamrznut na jeziku onoga ko je izazvao događaj, a ne onoga ko ga čita.

**Ključno olakšanje: red već nosi sve što treba za ponovno renderovanje** — `type` (stabilan enum `OutboxEventType`) i `payload` (`jsonb` sa `fullName`, `productName`, `orderCode`).

**Preporuka:** renderovati na čitanju, iz `type` + `payload`, kroz frontend JSON (`notifications.userRegistrationRequested.title` sa interpolacijom `{{fullName}}`). `title`/`message` kolone ostaju kao **zamrznuti fallback** za istorijske redove i za e-mail koji je već poslat. Ne brisati ih. Za e-mail (koji stvarno mora biti renderovan na serveru) vidi §12.3.

### 3.5 Seed nazivi u migracijama

Postojeće migracije upisuju srpske nazive direktno (`'Standardni obračun'`, `'Satnica'`, `'Ručna korekcija vremena'`). To je u skladu sa Varijantom A i ostaje. Nove migracije koje seed-uju šifrarnike moraju upisati **glavni red sa kodom + prevode**, po obrascu iz `2026-07-27-04` (guard `WHERE NOT EXISTS`, join po kodu).

**Ne izmišljati ruski prevod u migraciji.** Vidi §17.

### 3.6 Eksplicitno NE prevoditi

| Šta | Zašto |
|---|---|
| `operations.op_name`, `operations.description` | Korisnički unos po proizvodu, nije šifrarnik. Hiljade redova, jedinstveni po proizvodu. |
| `products.product_name`, `products.description` | Isto — poslovni podatak, ne šifrarnik. |
| `roles.role_name` | To **jeste** kod (`ROLE_<roleName>` u `CustomUserDetails`). Prevod ide u frontend JSON po vrednosti. |
| `*.note`, `*.description` na transakcionim tabelama (`payroll_adjustments`, `payroll_run_item_categories`, `monthly_report_categories`, `daily_report_categories`, `work_logs`, `scraps`, `production_order_line_item_notes`, `sample_order_line_item_notes`, `employee_records`) | Slobodan korisnički tekst. Tvoj §14 je ovde apsolutno u pravu i to je već ugrađeno pravilo projekta (`compensation-schemes-and-category-localization.md` §11). |
| `payroll_adjustments.override_reason` | Obavezan poslovni razlog koji je čovek uneo — istorijski zapis. |
| `work_code_category_mappings.note`, `work_code_category_scheme_rules.note` | Interne beleške za administratora. |
| `users.*` | Lični podaci. |
| `audit_logs.changes` | Nikad. Struktuiran `jsonb` snapshot. |
| `user_saved_views.name`, `mailing_lists.name` | Korisnik ih sam imenuje. |
| `livac_categories`, `plastic_categories` | **Dormantne tabele** — postoje u šemi, ali **nemaju JPA entitet** i nigde se ne koriste. Preskočiti. |
| `app_settings.description`, `employee_payroll_value_definitions.description` | Interne, na engleskom, pisane za programera. |
| Statusni enumi (`DRAFT`, `CALCULATED`, `APPROVED`, `LOCKED`, `CANCELLED`, `CREATED`, `DELIVERED`) | Stabilni u bazi i API-ju. Prevode se **samo labele**, kroz frontend JSON. Već je pravilo (`…localization.md` §11: „Locale never affects a number"). |

---

## 4. Preporučena locale strategija

### 4.1 Podržani lokali

```
sr-Latn   (podrazumevani)
sr-Cyrl
en
ru
```

BCP 47, tačno kako si tražio. `sr-Latn` ostaje podrazumevani jer to **već jeste** `AppLocales.DEFAULT`, jer su svi postojeći podaci na latinici, i jer bi promena podrazumevanog lokala bila nepotrebna migracija sa nula koristi.

### 4.2 Kako se tretira `sr` — odluka

**`sr` je alias za `sr-Latn`. Ne čuva se u bazi kao poseban prevod.** Odgovara tvojoj opciji 3 (normalizacija na nivou aplikacije) kombinovanoj sa opcijom 1 (koji je cilj aliasa).

Obrazloženje:

1. Tvoja premisa je tačna — tri nezavisna prevoda za `sr`, `sr-Latn` i `sr-Cyrl` bi se razišla.
2. `sr` **već postoji u bazi** kao `user_preferences.language` default (`'sr'`), pa normalizacija nije teorijska higijena nego popravka postojećeg stanja.
3. Latinica je izabrana jer su svi postojeći podaci na latinici, `AppLocales.DEFAULT` je već `sr-Latn`, a `dayjs/locale/sr` (koji je već importovan na 3 mesta) jeste latinična varijanta.
4. **Zaštita:** `UNIQUE (entity_id, lower(locale))` sprečava da `sr` i `SR` koegzistiraju; a `CHECK (locale IN (...))` bez `'sr'` u listi sprečava da `sr` red ikada uđe u translation tabelu. Normalizacija se dešava pre upisa, ne posle.

### 4.3 Tabela normalizacije

| Ulaz | Izlaz |
|---|---|
| `sr`, `sr-RS`, `sr-Latn`, `sr-Latn-RS`, `sr_Latn`, `SR-LATN` | `sr-Latn` |
| `sr-Cyrl`, `sr-Cyrl-RS`, `sr-CYRL` | `sr-Cyrl` |
| `en`, `en-US`, `en-GB`, `en-AU`, `EN` | `en` |
| `ru`, `ru-RU`, `RU` | `ru` |
| bilo šta drugo, `null`, `""`, `"   "` | `sr-Latn` (podrazumevani) |

Pravila:

- **Poređenje je case-insensitive** — to je već ponašanje `AppLocales.normalize()` i `UNIQUE (…, lower(locale))` indeksa, i već je testirano (`CategoryTranslationIT.unknownLocaleFallsBackToDefault` proverava `EN` → `en`).
- **Kanonski oblik čuvan u bazi je case-sensitive u zapisu** (`sr-Latn`, ne `sr-latn`) — BCP 47 konvencija: jezik mala slova, skript Title Case, region VELIKA.
- **Regionalne varijante se odbacuju**, ne odbijaju. `en-GB` → `en`. Nema poslovnog razloga za razlikovanje `en-US` i `en-GB` u ovom sistemu; ako se ikada pojavi (npr. format datuma), rešava se na sloju formatiranja, ne u translation tabeli.
- **Podvlaka se prihvata** (`sr_Latn`) jer je to čest oblik iz Java `Locale.toString()`.
- Parsiranje ide **od najspecifičnijeg ka najopštijem**: puni tag → jezik+skript → jezik → default. Ne po prefiks-matchu na sirovom stringu (`sr-Cyrl` počinje sa `sr`, pa bi naivni `startsWith` dao pogrešan rezultat).

### 4.4 Šta se menja u `AppLocales`

`AppLocales` ostaje jedina tačka istine, ali dobija:

```java
DEFAULT   = "sr-Latn"
SERBIAN_CYRILLIC = "sr-Cyrl"
ENGLISH   = "en"
RUSSIAN   = "ru"
SUPPORTED = ordered Set { sr-Latn, sr-Cyrl, en, ru }   // LinkedHashSet — redosled je UI redosled

normalize(String)          // proširen tabelom iz §4.3
isSupported(String)        // case-INsensitive — zamenjuje SUPPORTED.contains() u EmployeeService
fallbackChain(String)      // vidi §15
```

`normalize()` zadržava „opraštajuće" ponašanje (nepoznat locale → default, nikad izuzetak) za **read** putanje. Za **write** putanje (postavljanje `preferred_locale`, upis prevoda) koristi se `isSupported()` koji vraća `false` i vodi u 400 sa jasnom porukom — jer tiho pretvaranje `"ru"` u `"sr-Latn"` pri **upisu** prevoda bi prepisalo pogrešan red.

**Ova razlika je važna i danas ne postoji.** To je uzrok nekonzistentnosti (B).

---

## 5. Odluka: kanonski naziv u glavnoj tabeli (Varijanta A vs B)

### 5.1 Preporuka: **Varijanta A** — naziv ostaje u glavnoj tabeli

```
compensation_schemes
  id, code, name              ← srpski naziv ostaje ovde
compensation_scheme_translations
  id, compensation_scheme_id, locale, name    ← sr-Cyrl, en, ru
```

Tvoja početna sklonost je bila Varijanta B (svi nazivi u translation tabeli, uključujući srpski). **Preporučujem A, i to iz razloga specifičnih za ovaj projekat, ne iz opšte lenjosti.**

### 5.2 Zašto A, konkretno ovde

1. **Projekat je već izabrao A, dvaput, i to eksplicitno dokumentovao.**
   Migracija `2026-07-27-04` u komentaru piše: *„sr-Latn is deliberately NOT seeded. It is served from `category_name`, so there is exactly one place to edit a Serbian name and nothing to drift out of sync."*
   Prelazak na B za nove entitete uveo bi **dva različita obrasca u istom sistemu** — najgori mogući ishod. Prelazak na B i za postojeća dva zahteva migraciju podataka i prepisivanje oba resolvera i `nameEn` API-ja, bez ijedne nove sposobnosti.

2. **Fallback ne može da otkaže.** Kod A je `master.name` `NOT NULL`, pa `COALESCE` uvek daje neprazan string. Kod B, `displayName` može biti `null` ako niko nije uneo osnovni prevod — a ovo su **obračunski listovi**. Prazna labela pored iznosa je gora od labele na pogrešnom jeziku.

3. **`ON DELETE CASCADE` je bezbedan samo kod A.** Kod B, brisanje glavnog reda briše i jedini postojeći naziv — entitet postaje neimenovan pre nego što nestane, a `audit_logs.changes` (koji snima `to_jsonb(OLD)` glavnog reda) više ne bi sadržao naziv. Auditabilnost je izričito zaštićena vrednost ovog projekta.

4. **Postojeći API bi pukao.** `WorkCodeCategoryDto` ima i `name` (master) i `displayName` (razrešen) i `nameEn`; `PayrollAdjustmentDetailDto` ima `categoryName` + `categoryDisplayName`. Frontend danas čita `categoryDisplayName ?? categoryName` — fallback koji **postoji upravo zato što master naziv postoji**. Varijanta B ga čini besmislenim.

5. **Migracija je nula redova.** Kod A ne treba backfill postojećih srpskih naziva u translation tabelu. Kod B treba backfill svih redova svih šifrarnika — a zatim treba i dokazati da nijedan nije ostao bez `sr-Latn` reda, i to zauvek održavati.

### 5.3 Rizik Varijante A i kako se neutrališe

Tvoj prigovor je tačan: `name` postaje **implicitno vezan za jedan locale**. Neutralizacija u tri koraka:

1. `COMMENT ON COLUMN <t>.name IS 'Default display name. The locale is AppLocales.DEFAULT (sr-Latn). Not a code — never key logic on it.'` — obrazac koji projekat već koristi obilno.
2. `AppLocales.DEFAULT` je **jedna konstanta**; ako se podrazumevani locale ikada promeni, to je izmena na jednom mestu plus migracija koja premesti stari `name` u eksplicitan `sr-Latn` red. Put ka B ostaje otvoren, ne zatvara se.
3. Administrativni ekran (§18) prikazuje `sr-Latn` polje kao **jednakopravan tab**, koji piše u `master.name`, a ne u translation tabelu. Korisnik ne vidi asimetriju; ona postoji samo u skladištu.

### 5.4 Poređenje sve četiri opcije iz tvog §8

| | A: naziv u glavnoj tabeli | B: svi nazivi u translation tabeli | C: generička polimorfna tabela | D: JSONB kolona prevoda |
|---|---|---|---|---|
| **Referencijalni integritet** | ✅ pravi FK | ✅ pravi FK | ❌ `entity_type`+`entity_id` bez FK | ✅ (nema šta da se lomi) |
| **Fallback ne može da otkaže** | ✅ `NOT NULL` master | ⚠️ zavisi od obaveznog prevoda | ⚠️ isto | ⚠️ isto |
| **Migracija postojećih podataka** | ✅ nula redova | ❌ backfill svih šifrarnika | ❌ backfill + `entity_type` konvencija | ❌ backfill |
| **Kompatibilnost sa `audit_trigger_fn`** | ✅ (surogat `id`) | ✅ | ⚠️ `record_id` gubi značenje | ✅ (audituje se glavni red) |
| **Sortiranje/pretraga po prevedenom nazivu u SQL-u** | ✅ join + `COALESCE` | ✅ join | ⚠️ join sa filterom po `entity_type` | ❌ `jsonb ->>` bez dobrog indeksa |
| **Provera „koji prevodi nedostaju"** | ✅ trivijalan `LEFT JOIN` | ✅ | ⚠️ | ❌ teško |
| **Dodavanje novog jezika** | ✅ samo redovi | ✅ | ✅ | ⚠️ `UPDATE` svakog reda |
| **`NOT NULL` na prevodu** | ✅ per-red | ✅ | ⚠️ | ❌ nema |
| **Konzistentnost sa postojećim kodom** | ✅ **to je već obrazac** | ❌ | ❌ | ❌ |
| **Broj novih tabela** | 4–6 | 4–6 | 1 | 0 |
| **Rizik: `name` implicitno vezan za locale** | ⚠️ (mitigacija §5.3) | ✅ nema | ✅ nema | ✅ nema |

**Konačna preporuka: Varijanta A, po-entitetska translation tabela.**
Generička polimorfna tabela odbačena — nema FK, `audit_trigger_fn().record_id` gubi značenje, i tvoj zahtev je izričito protiv nje bez „veoma jakog razloga"; nijedan nije nađen.
JSONB odbačen — ne može `NOT NULL` po prevodu, sortiranje po lokalizovanom nazivu postaje neindeksirano, i izveštaj o nedostajućim prevodima postaje mučan.

---

## 6. Preporučeni DB translation model

### 6.1 Šablon (nastavak postojećeg oblika iz §1.1)

```sql
CREATE TABLE IF NOT EXISTS <entity>_translations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    <entity>_id     BIGINT       NOT NULL,
    locale          VARCHAR(35)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,

    CONSTRAINT fk_<abbr>_entity FOREIGN KEY (<entity>_id)
        REFERENCES <entity_table>(id) ON DELETE CASCADE,
    CONSTRAINT chk_<abbr>_locale_not_empty CHECK (length(trim(locale)) > 0),
    CONSTRAINT chk_<abbr>_locale_supported
        CHECK (locale IN ('sr-Latn','sr-Cyrl','en','ru')),      -- NOVO, vidi 6.3
    CONSTRAINT chk_<abbr>_name_not_empty   CHECK (length(trim(name)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_<abbr>_entity_locale
    ON <entity>_translations (<entity>_id, lower(locale));
CREATE INDEX IF NOT EXISTS idx_<abbr>_locale
    ON <entity>_translations (lower(locale));

CREATE TRIGGER trg_03_<abbr>_updated_at BEFORE UPDATE ON <entity>_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO audit_tables (table_name) SELECT '<entity>_translations'
WHERE NOT EXISTS (SELECT 1 FROM audit_tables WHERE table_name = '<entity>_translations');

CREATE TRIGGER trg_audit_logs_<entity>_translations
    AFTER INSERT OR UPDATE OR DELETE ON <entity>_translations
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_fn();
```

### 6.2 Analiza svake kolone iz tvog §4 — šta uzimamo i zašto

| Kolona | Uzeti? | Obrazloženje |
|---|---|---|
| `id` (surogat) | ✅ **DA, obavezno** | Ne zbog stila — `audit_trigger_fn()` upisuje `record_id := NEW.id`. Bez `id` kolone prevod se **ne može auditovati**, a obe postojeće translation tabele su auditovane. Ovo je jedini razlog i dovoljan je. |
| `entity_id` | ✅ | FK, `ON DELETE CASCADE`. |
| `locale` | ✅ | `VARCHAR(35)` — isto kao postojeće. Dovoljno za bilo koji BCP 47 tag. |
| `name` | ✅ | `NOT NULL` + `CHECK (trim ≠ '')`. Prazan prevod je gori od nepostojećeg — nepostojeći aktivira fallback, prazan proizvodi praznu labelu. |
| `short_name` | ❌ **NE** | Nijedan od šest kandidat-entiteta danas nema `short_name`/skraćeni naziv. Dodavanje kolone za nepostojeći koncept. Ako zatreba, dodaje se migracijom kasnije — kolone se dodaju jeftino, uklanjaju skupo. |
| `description` | ⚠️ **samo za `payroll_time_adjustment_categories`** | Jedini kandidat čiji je `description` (a) neprazan i (b) korisnički. Kod `app_settings` i `employee_payroll_value_definitions` je interni, na engleskom, pisan za programera → §3.2. Ne dodavati kolonu tamo gde nema šta da drži. |
| `created_at` | ✅ | Postoji na obe postojeće. |
| `updated_at` | ✅ | Postoji, sa `set_updated_at()` triggerom. |
| `created_by` / `updated_by` | ❌ **NE** | **Projekat već ima standardni audit mehanizam i ovo bi ga duplirao.** `audit_trigger_fn()` upisuje `user_id` iz `current_setting('app.user_id')`, koji postavlja `AuditUserAspect`. Obe postojeće translation tabele oslanjaju se isključivo na to i **nemaju** `created_by`/`updated_by`. Dodavanje bi značilo dva izvora istine o tome ko je promenio prevod. |
| `version` (optimistic lock) | ⚠️ **odloženo** | Nijedna postojeća translation tabela ga nema. Konkurentna izmena istog prevoda je realan ali redak scenario (mali tim, administratorski ekran). **Preporuka:** ne u fazi 3; dodati ako i kada ekran za uređivanje prevoda dobije više istovremenih korisnika. Ako se doda, `@Version` na entitetu + kolona, i `GlobalExceptionHandler` već ima 409 handler za `OptimisticLockingFailureException`. |
| `is_active` / soft delete | ❌ **NE** | Prevod nije poslovni zapis sa životnim ciklusom. „Neaktivan prevod" je isto što i „nepostojeći prevod" — a nepostojeći se već obrađuje fallback-om. Brisanje reda je ispravna operacija i audituje se kao `delete`. |
| Vremensko verzionisanje (`valid_from`/`valid_until`) | ❌ **NE** | **Ovo je važna odluka.** Prevod **nije deo poslovne istorije** — on je trenutni prikaz naziva. Sistem već pravi ovu razliku eksplicitno: `work_logs.norm_multiplier_snapshot` snima *koeficijent* (poslovna vrednost) jer utiče na iznos, dok `payroll_run_item_categories` **namerno ne snima naziv** i razrešava ga kroz master red (`…localization.md` §11: kopiranje naziva na transakcioni red „guarantee they diverge the first time someone corrects a typo"). Ispravka tipfelera u prevodu treba da se odrazi i na stare obračune. Sam prevod je auditovan, pa je istorija promena ipak sačuvana u `audit_logs`. |

### 6.3 Jedno odstupanje od postojećeg oblika: `CHECK` na locale

Postojeće dve tabele nemaju `CHECK (locale IN (...))` — samo `CHECK (length(trim(locale)) > 0)`. Predlažem da **nove tabele imaju** ograničenje na podržan skup, i da se **postojeće dve dopune** istim ograničenjem u istoj migraciji.

Razlog: `employees.preferred_locale` **već** ima takav `CHECK`, pa je to obrazac projekta; a bez njega ništa ne sprečava da neko upiše `'sr'` red i time napravi upravo dupliranje koje tvoj zahtev zabranjuje.

**Trošak koji treba prihvatiti:** dodavanje petog jezika postaje migracija (`ALTER … DROP CONSTRAINT` + `ADD CONSTRAINT`), a ne samo izmena `AppLocales`. To je prihvatljivo — dodavanje jezika je ionako projekat (prevod ~1500 ključeva), ne konfiguracija.

**Alternativa ako se ovo ne želi:** referentna tabela `supported_locales (code PK, display_name, sort_order, is_active)` i FK sa translation tabela na nju. Čistije za budućnost, ali uvodi novu tabelu i FK na svaki prevod. **Odluka D-4.**

---

## 7. Lista tabela koje treba DODATI

| # | Nova tabela | Za entitet | Kolone | Faza |
|---|---|---|---|---|
| 1 | `compensation_scheme_translations` | `compensation_schemes` | id, compensation_scheme_id, locale, name | 4 (pilot) |
| 2 | `payroll_time_adjustment_category_translations` | `payroll_time_adjustment_categories` | id, …_id, locale, name, **description** | 5 |
| 3 | `employee_payroll_value_definition_translations` | `employee_payroll_value_definitions` | id, …_id, locale, name | 5 |
| 4 | `app_setting_translations` | `app_settings` | id, app_setting_id, locale, display_text, unit | 6 |
| 5 | `bonus_category_translations` | `bonus_categories` | id, bonus_category_id, locale, name | 6 |
| 6 | `shift_translations` | `shifts` | id, shift_id, locale, name | 6 |
| 7 | *(opciono)* `supported_locales` | — | code, display_name, sort_order, is_active | 1, samo ako se usvoji D-4 |

Napomena za #4: kolona se zove `display_text` (ne `name`) da preslikava master kolonu — konzistentnost sa `app_settings.display_text` je važnija od uniformnosti između translation tabela.

## 8. Lista postojećih tabela koje treba MENJATI

| Tabela | Izmena | Breaking? | Faza |
|---|---|---|---|
| `employees` | `DROP CONSTRAINT chk_employees_preferred_locale` → `ADD CHECK (preferred_locale IN ('sr-Latn','sr-Cyrl','en','ru'))` | Ne (proširenje skupa) | 1 |
| `user_preferences` | 1) `UPDATE … SET language='sr-Latn' WHERE lower(language) IN ('sr','sr-rs','sr-latn-rs')`; 2) `ALTER … ALTER COLUMN language SET DEFAULT 'sr-Latn'`; 3) `ADD CHECK (language IN ('sr-Latn','sr-Cyrl','en','ru'))`; 4) proširiti `VARCHAR(10)` → `VARCHAR(35)` radi konzistentnosti | Ne (default i backfill, postojeći korisnici zadržavaju ponašanje) | 1 |
| `work_code_category_translations` | `ADD CHECK (locale IN (…))` | Ne (postojeći redovi su `en`) | 1 |
| `payroll_adjustment_category_translations` | `ADD CHECK (locale IN (…))` | Ne | 1 |
| `work_calendar_days` | `ADD COLUMN holiday_key VARCHAR(60)` + backfill iz postojećih `label` vrednosti po mapi | Ne (nullable) | 6 |
| `departments` | `ADD COLUMN code VARCHAR(60)` + backfill + `UNIQUE` — **samo ako se usvoji D-5** | Ne | 6 |
| `notification_events` | Bez izmene šeme. Menja se samo **čitanje** (render iz `type`+`payload`). | Ne | 8 |

**Ništa se ne uklanja.** Nijedno postojeće `name` polje ne nestaje — Varijanta A ih zadržava trajno kao podrazumevani naziv i fallback.

---

## 9. Fallback algoritam

### 9.1 Lanac

```
zahtevani locale
  → normalizuj (§4.3)
  → prevod za normalizovan locale
  → prevod za fallback-roditelja (samo sr-Cyrl → sr-Latn)
  → master.name (uvek NOT NULL)
  → code                       ← nedostižno kod Varijante A
```

Konkretno po jeziku:

```
sr-Latn  →  master.name
sr-Cyrl  →  sr-Cyrl prevod  →  master.name (sr-Latn)
en       →  en prevod       →  master.name (sr-Latn)
ru       →  ru prevod       →  master.name (sr-Latn)
```

### 9.2 Zašto `sr-Cyrl → sr-Latn`, a `ru` NE ide preko `en`

`sr-Cyrl → sr-Latn` je jedini skriptni fallback koji ima smisla: isti jezik, drugo pismo, čitalac ćirilice čita latinicu. To je i realno stanje — `sr-Latn` je podrazumevani srpski prikaz.

`ru → sr-Latn` (a **ne** `ru → en → sr-Latn`) jer bi lanac preko engleskog bio proizvoljan: ruski čitalac nema veći razlog da razume engleski nego srpski, a dvostepeni fallback čini nemogućim iz odgovora zaključiti *koji* prevod nedostaje. Jedan skok, uvek na podrazumevani.

### 9.3 Odgovori na tvoja pitanja iz §7

| Pitanje | Odgovor |
|---|---|
| Šta ako traženi prevod ne postoji? | `master.name` (srpski latinični). Nikad `null`, nikad prazno. |
| Šta ako ne postoji ni podrazumevani? | **Nemoguće** kod Varijante A — `master.name` je `NOT NULL` sa `CHECK (trim ≠ '')`. To je ključna prednost A nad B. |
| Da li se prikazuje `code`? | Ne u normalnom radu. `code` ostaje krajnji fallback samo u kodu (defanzivno), i ako se ikada aktivira to je bug, ne dizajn. |
| Da li backend prijavljuje grešku? | **Ne za read.** UI nikad ne sme da pukne zbog prevoda. Za **write** neподržanog locale-a → 400. |
| Da li nedostajući prevod blokira kreiranje/aktivaciju? | **Ne.** Vidi §10. |
| Da li je dovoljan samo osnovni srpski? | **Da**, za kreiranje i aktivaciju. |
| Kako se nedostajući prevodi vide? | (1) `translationFallbackUsed` flag u odgovoru; (2) `WARN` log jednom po (entitet, locale) po zahtevu, ne po redu; (3) admin ekran §18 sa statusom kompletnosti; (4) dijagnostički endpoint `GET /api/admin/translations/completeness`. |

### 9.4 Centralna komponenta — jedan resolver, ne šest

Danas postoje dva **skoro identična** resolvera (`WorkCodeCategoryNameResolver`, `PayrollAdjustmentCategoryNameResolver`) sa istim ugovorom. Sa šest novih entiteta to bi postalo osam kopija istog fallback-a — tačno ono što tvoj §19 zabranjuje.

**Preporuka:** izvući generičku bazu u `common/i18n/`:

```
common/i18n/AppLocales.java              (postoji — proširiti)
common/i18n/LocaleResolver.java          (NOVO — Accept-Language + user pref + ?locale=, §16)
common/i18n/TranslationResolver<T>.java  (NOVO — batch translationsFor(locale) + displayName(id, default, map))
common/i18n/TranslationRepository<T>     (NOVO — zajednički interfejs: findAllByLocale, findByEntityAndLocale)
common/i18n/TranslationAdminService<T>   (NOVO — upsert/delete + validacija, §18)
```

Postojeća dva resolvera zadržavaju svoje ime i javni ugovor (`translationsFor` / `displayName`) i postaju tanki podklasovi. **Nijedan postojeći pozivalac se ne menja** — to je uslov, jer su ti pozivi na payroll putanji.

**Batch disciplina je već ugrađena i mora se očuvati:** oba resolvera nude *samo* oblik upita „svi prevodi za ovaj locale", jer je read putanja obračunski list sa desetinama redova. Javadoc to naziva „the mistake this class exists to prevent". Generička baza mora zadržati isto ograničenje — nema `displayName(id, locale)` metode koja radi upit, osim eksplicitno označene „ne za petlje" (kakva postoji danas u `WorkCodeCategoryNameResolver:68`).

---

## 10. Obavezni prevodi i lifecycle

**Preporuka: tvoja opcija 1 — za kreiranje i za aktivaciju obavezan je samo `sr-Latn`.**

Kod Varijante A to je automatski zadovoljeno: `master.name` je `NOT NULL`, pa svaki zapis po konstrukciji ima osnovni naziv. **Nema dodatnog gate-a koji treba pisati.**

| | Zahtev |
|---|---|
| Minimalno za **kreiranje** | `sr-Latn` (= `master.name`, već `NOT NULL`) |
| Minimalno za **aktivaciju** (`is_active = true`) | isto — `sr-Latn` |
| `en`, `sr-Cyrl`, `ru` | **opciono**, uvek |

Zašto ne stroža pravila: sistem već ima gate za aktivaciju kod kompenzacionih šema (`2026-09-11-01-a-scheme-cannot-be-activated-with-a-gap.sql`, `PayrollActivationGateIT`) — i on postoji jer **rupa u matrici kategorija menja iznos na obračunskom listu**. Nedostajući ruski prevod ne menja nijedan iznos (`…localization.md` §11: „Locale never affects a number"). Blokirati aktivaciju šifrarnika zbog prevoda značilo bi izjednačiti kozmetički nedostatak sa finansijskim — i, praktično, zaustaviti rad kad god neko doda kategoriju u petak popodne.

**Umesto blokade — vidljivost:**

1. `GET /api/admin/translations/completeness` → po entitetu i lokalu: ukupno / prevedeno / nedostaje, sa listom kodova.
2. Admin ekran (§18) prikazuje badge „3/4 jezika" po redu i filter „nepotpuni".
3. Jedan `WARN` log po (entitet, locale) po zahtevu kada je fallback iskorišćen — ne po redu, da payroll petlja ne poplavi log.
4. **CI provera** (§21): svaki *sistemski* šifrarnik (`is_system = true` gde postoji) mora imati neprazan `master.name`. To je jedino obavezno pravilo i ono već važi kroz `NOT NULL`.

---

## 11. Preporučena struktura frontend JSON fajlova

### 11.1 Biblioteka

**Preporuka: `i18next` + `react-i18next` + `i18next-icu` (ili `intl-messageformat`).**

Obrazloženje vezano za ovaj projekat:

| Kriterijum | Zašto i18next |
|---|---|
| Ruska i srpska pluralizacija | Oba jezika imaju `one/few/many/other`. i18next ima ugrađen `Intl.PluralRules` backend; sa ICU formatom dobija se i `select`/`selectordinal`. **Ovo je najjači argument** — tvoj §11 traži padeže i pluralne forme, a ručno spajanje stringova je izričito zabranjeno. |
| Namespace-i po modulu | Prvoklasna podrška (`useTranslation('payroll')`), i mapira se 1:1 na postojeću `features/*` strukturu. |
| Electron / Vite | Radi bez server-side dela. JSON se može bundlovati statički (`resources` objekat) ili lazy-load-ovati. Za desktop app sa 4 jezika i ~1500 ključeva **preporučujem statički bundle** — nema mrežnog zahteva, nema flash-a neprevedenog teksta, a veličina je zanemarljiva. |
| Nedostajući ključ | `saveMissing` + `missingKeyHandler` → dev upozorenje; `parseMissingKeyHandler` → prikaži poslednji segment ključa umesto sirovog `payroll.fields.x`. |
| Zrelost | Daleko najveća baza, stabilan API, TypeScript tipovi za ključeve (`react-i18next` module augmentation daje **autocomplete i grešku pri build-u za nepostojeći ključ**). |

Odbačene alternative: `react-intl` (ICU je odličan, ali namespace/lazy-load je slabiji i tipizacija ključeva lošija); `lingui` (traži build plugin i macro — dodatna kompleksnost u već netrivijalnom Vite+Electron setup-u); ručno rešenje (pluralizacija za `ru` i `sr` je tačno ono što se ne piše ručno).

**Dodatno:** `dayjs` je već zavisnost i ima `sr`, `sr-cyrl`, `en`, `ru` lokale — koristiti `dayjs.locale()` sinhronizovan sa i18next, ne paralelno.
**Mantine** prima `locale` prop na `DatePicker`/`DatePickerInput` (danas hardkodovano `locale="sr"` na 6 mesta) i ima `DatesProvider` za globalno podešavanje — koristiti `DatesProvider` u `App.tsx` umesto po-komponentnog propa.

### 11.2 Struktura fajlova

```
src/ui/i18n/
  index.ts                 # init, detekcija, sinhronizacija sa dayjs/Mantine
  locales.ts               # SUPPORTED_LOCALES — mora se poklapati sa AppLocales
  resources/
    sr-Latn/
      common.json          # akcije, statusi, jedinice, potvrde, prazna stanja
      validation.json      # validacione poruke
      errors.json          # mapa backend error code → poruka
      nav.json             # navigacija, naslovi stranica, breadcrumbs
      employees.json
      payroll.json         # obračun + payroll-records
      shifts.json          # NAJVEĆI — features/shifts
      records.json
      products.json
      operations.json
      productionOrders.json
      manufacturingTimes.json
      analytics.json
      bonuses.json
      calendar.json        # work-calendar + employees-calendars + praznici
      settings.json
      notifications.json   # render notification_events iz type+payload
      documents.json       # PDF/eksport labele
    sr-Cyrl/  …isti fajlovi
    en/       …isti fajlovi
    ru/       …isti fajlovi
```

17 namespace-a × 4 jezika = 68 fajlova. Podela prati `features/` direktorijume, pa je „gde ide ovaj ključ" mehaničko pitanje.

### 11.3 Konvencija imenovanja ključeva

**Format:** `namespace:domen.tip.naziv`, **camelCase**, **maksimalna dubina 4 segmenta** (bez namespace-a).

```
common:actions.save
common:actions.cancel
common:actions.delete
common:status.payroll.draft
common:units.rsd
common:units.minutes

validation:required
validation:maxLength
validation:dateRequired

errors:PAYROLL_PERIOD_ALREADY_CLOSED     ← ključ JE backend code, VELIKA_SLOVA
errors:WORK_SHIFT_OVERLAP
errors:generic

payroll:title
payroll:actions.calculate
payroll:fields.transportAllowance
payroll:sections.additions
payroll:errors.noSchemeForPeriod

shifts:table.headers.employee
shifts:modals.createLog.title
```

Pravila:

1. **camelCase**, ne snake_case — poklapa se sa TypeScript konvencijom celog repo-a.
2. **Maksimalno 4 segmenta** posle namespace-a. Dublje znači da namespace treba podeliti.
3. **Nikad ključ zasnovan na rečenici.** `"Sačuvaj promene": "Save changes"` je zabranjeno — traženo u zahtevu i tačno.
4. **`errors:` namespace je izuzetak:** ključ je doslovno backend `code` (`ACCOUNT_NOT_USABLE`, `WORK_SHIFT_OVERLAP`). Tako je mapiranje mehaničko i nemoguće ga je pogrešno napisati.
5. **Statusi i enumi**: `common:status.<domain>.<VALUE_lowercase>`, npr. `common:status.payroll.locked`. Ovo direktno zamenjuje 5 postojećih `Record<Code, string>` mapa iz §2.3.
6. **Šifrarnici iz baze NIKAD nemaju JSON ključ.** Njihov naziv dolazi kao `displayName` iz API-ja. Jedina veza: ako `displayName` fali, prikazuje se `code` — bez prevoda.

### 11.4 Pluralizacija i interpolacija

Obavezno kroz biblioteku. Zabranjena konkatenacija.

```jsonc
// sr-Latn/employees.json
{
  "count_one":  "{{count}} radnik",
  "count_few":  "{{count}} radnika",
  "count_other":"{{count}} radnika"
}
// ru/employees.json
{
  "count_one":  "{{count}} работник",
  "count_few":  "{{count}} работника",
  "count_many": "{{count}} работников",
  "count_other":"{{count}} работника"
}
```

```ts
t('employees:count', { count })            // ✅
`${count} radnika`                          // ❌ ESLint pravilo iz §21 ovo hvata
```

Srpski i ruski koriste iste `Intl.PluralRules` kategorije (`one/few/many/other`), engleski samo `one/other` — i18next to rešava automatski po `_one`/`_few`/`_many`/`_other` sufiksima.

**Padeži:** srpski/ruski padeži se **ne rešavaju** interpolacijom imenice u rečenicu. Pravilo: **cela rečenica je ključ**, promenljive su brojevi i datumi, ne imenice.

```
❌ t('deleteConfirm', { entity: t('employee') })   // „Obrisati zaposleni?"
✅ employees:delete.confirm  = "Da li želite da obrišete zaposlenog?"
✅ products:delete.confirm   = "Da li želite da obrišete proizvod?"
```

Ovo pravilo je jedini pouzdan način i mora biti u dokumentaciji (§26), jer ga je lako prekršiti dobrom namerom (DRY).

### 11.5 Formatiranje — datumi, brojevi, valuta

Zamenjuje **43 hardkodovana `sr-RS`/`sr-Latn-RS` literala**.

```ts
// src/ui/i18n/format.ts (NOVO)
export function useFormat() {
  const { i18n } = useTranslation();
  const l = intlLocale(i18n.language);   // sr-Latn→sr-Latn-RS, sr-Cyrl→sr-Cyrl-RS, en→en-US, ru→ru-RU
  return {
    money:    (v) => new Intl.NumberFormat(l, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v),
    number:   (v, d = 2) => new Intl.NumberFormat(l, { maximumFractionDigits: d }).format(v),
    percent:  (v) => new Intl.NumberFormat(l, { style: 'percent', minimumFractionDigits: 2 }).format(v / 100),
    date:     (v) => new Intl.DateTimeFormat(l, { day: '2-digit', month: '2-digit', year: 'numeric' }).format(v),
    dateLong: (v) => new Intl.DateTimeFormat(l, { day: '2-digit', month: 'long', year: 'numeric' }).format(v),
    time:     (v) => new Intl.DateTimeFormat(l, { hour: '2-digit', minute: '2-digit' }).format(v),
    duration: (min) => …,  // „7 h 30 min" — kroz t() sa interpolacijom, ne konkatenacijom
  };
}
```

Očekivano ponašanje:

```
sr-Latn / sr-Cyrl:  1.234,50 RSD     05.08.2026.
en:                 1,234.50 RSD     08/05/2026
ru:                 1 234,50 RSD     05.08.2026
```

**Valuta ostaje RSD u svim jezicima.** Sistem plaća u dinarima; prevod jezika ne menja valutu. Simbol/kod se prikazuje kao `RSD` (ne `дин.`), jer je to trenutno ponašanje i menjanje bi zbunilo. **Ne koristiti `style: 'currency'`** — `Intl` bi za `ru-RU` stavio `RSD` iza broja sa nekonzistentnim razmakom; formatiranje broja + literal `RSD` iz `common:units.rsd` daje kontrolu.

**Poslovne vrednosti u bazi i API-ju ostaju numeričke.** Formatiranje je isključivo prikazni sloj — to je već pravilo (`…localization.md` §11) i ne menja se.

**Prvi dan u nedelji:** ponedeljak za sva četiri jezika (`sr`, `ru` prirodno; `en-US` bi bio nedelja — **eksplicitno postaviti ponedeljak**, jer je to fabrička radna nedelja, ne kulturna konvencija). Isto za `EmployeeCalendarMonthView.WEEK_LABELS`.

---

## 12. Backend poruke i greške

### 12.1 Podela

| Vrsta | Jezik | Gde se prevodi |
|---|---|---|
| **Domenske greške** (409, 400 poslovna pravila) | stabilan `code` + parametri | **frontend**, `errors:` namespace |
| **Tehničke greške** (500) | generička poruka | frontend |
| **Bean-validation** (`@NotNull(message=…)`) | ključ umesto teksta | frontend, `validation:` namespace |
| **Log poruke** | engleski, **nikad se ne prevode** | — |
| **Audit** (`audit_logs.changes`) | struktuiran `jsonb`, **nikad tekst** | — |
| **In-app notifikacije** | `type` + `payload` | frontend, `notifications:` namespace (§3.4b) |
| **E-mail** | mora se renderovati na serveru | **backend**, §12.3 |
| **PDF / eksport** | generiše ih frontend (`@react-pdf/renderer`) | **frontend**, `documents:` namespace |

### 12.2 Oblik odgovora na grešku

Današnji oblik (`GlobalExceptionHandler`) je `{ timestamp, error }`, ponegde `+ code`, `+ details`. Predlog **aditivnog** proširenja:

```json
{
  "timestamp": "2026-08-05T10:22:00",
  "code": "PAYROLL_PERIOD_ALREADY_CLOSED",
  "messageKey": "errors:PAYROLL_PERIOD_ALREADY_CLOSED",
  "params": { "month": "2026-07" },
  "error": "Obračunski period 2026-07 je već zaključan."
}
```

**`error` polje OSTAJE** i zadržava srpski tekst. Razlog: frontend ga danas prikazuje na 6+ mesta (`Login.tsx:55`, `Registration.tsx:165`, `CompensationSchemeSection.tsx:21`, …). Uklanjanje bi bilo breaking. Postaje **fallback za slučaj da frontend nema ključ**.

Migracija je postepena i po grešci: `ConflictException` dobija opcione `code` + `params`; postojeći pozivi bez njih rade kao i pre.

**Broj domenskih grešaka koje treba kodirati: procenjeno 60–90** od 196 srpskih literala (ostalo su bean-validation poruke i interne).

### 12.3 E-mail — jedini slučaj koji traži server-side lokalizaciju

`notification_deliveries` + `EmailSender` (danas `LoggingEmailSender`, pravi provajder još nije priključen — `IMPLEMENTATION-STATUS.md` §5.3).

E-mail se šalje bez browsera, pa ga frontend ne može renderovati. **Preporuka:** kada se pravi `EmailSender` dodaje, dodati Spring `MessageSource` sa `messages_sr-Latn.properties` / `_en` / `_ru` / `_sr-Cyrl` **isključivo za e-mail template-e**, i renderovati po `user_preferences.language` primaoca.

**Ne uvoditi `MessageSource` za API greške** — to bi napravilo dva izvora istine za istu poruku (jedan u `.properties`, drugi u JSON-u). API greške idu kroz `code` + frontend.

Pošto `EmailSender` još nije implementiran, **ovo se ne radi sada** — samo se zapisuje kao ograničenje za onoga ko ga bude pisao.

---

## 13. API locale strategija

### 13.1 Prioritet razrešavanja

**Za obične read endpointe (UI podaci):**

```
1. ?locale= query parametar          (eksplicitan override, npr. preview)
2. Accept-Language header             (šalje ga apiClient iz i18n stanja)
3. user_preferences.language          (za autentifikovanog korisnika)
4. AppLocales.DEFAULT                 (sr-Latn)
```

**Za dokumente O ZAPOSLENOM (obračunski list, potvrda):**

```
1. ?locale= query parametar           (preview)
2. employees.preferred_locale         ← NE korisnikov jezik
3. AppLocales.DEFAULT
```

**Ovaj drugi lanac je već implementiran** u `PayrollRunItemService:469,600-602` i njegov razlog je dokumentovan (`…localization.md` §11: „A payroll PDF is a document **about the employee**, so it uses `employees.preferred_locale`, not the preference of the clerk who opened it."). Ne dirati ga — samo ga proširiti na nove jezike.

Razlika je suštinska i mora ostati: administrator koji radi na srpskom može generisati obračun za ruskog radnika na ruskom.

### 13.2 Prenos

- **`Accept-Language` header**, postavljan centralno u `apiClient.buildHeaders()` (`src/ui/lib/apiClient.ts:37-49`) — **jedna tačka izmene za ceo frontend.**
- `?locale=` ostaje na postojeća 3 endpointa (backward compatible) i dodaje se samo tamo gde treba eksplicitan override.
- **Ne uvoditi locale u telo zahteva** — nije deo poslovnih podataka.

### 13.3 Centralna komponenta

```java
common/i18n/LocaleResolver.java   // NOVO
  String resolveForUi(HttpServletRequest req, Long userId)
  String resolveForEmployeeDocument(Employee employee, String explicitLocale)
```

Implementirano kao `@RequestScope` bean ili `HandlerMethodArgumentResolver` (da kontroleri mogu primiti `@ResolvedLocale String locale` bez ponavljanja). **Nijedan servis ne sme sam da implementira fallback.**

### 13.4 Ponašanje pri nepodržanom locale-u

| Putanja | Ponašanje |
|---|---|
| **Read** (`?locale=de`, `Accept-Language: de`) | Tiho → `AppLocales.DEFAULT`. Nikad greška. `resolvedLocale` u odgovoru kaže šta je stvarno korišćeno. |
| **Write** (`PUT …/translations/de`, `preferredLocale: "de"`) | **400** sa listom podržanih. Tiho pretvaranje bi upisalo prevod u pogrešan red. |

### 13.5 Metapodaci u odgovoru

Tvoj predlog iz §9 (`resolvedLocale`, `requestedLocale`, `translationFallbackUsed`) je koristan, ali **ne po redu** — obračunski list ima desetine redova i to bi utrostručilo payload.

**Preporuka:** na nivou **omotača odgovora**, ne stavke:

```json
{
  "resolvedLocale": "ru",
  "translationsComplete": false,
  "items": [ { "id": 1, "code": "MEAL_ALLOWANCE", "displayName": "Топли оброк", … } ]
}
```

Za postojeće endpointe koji vraćaju gole liste — **ne menjati oblik** (breaking). Umesto toga, HTTP header:

```
Content-Language: ru
X-Translation-Fallback-Used: true
```

Header je aditivan, ne lomi nijednog klijenta, a dev alati ga vide.

### 13.6 Batch i eksport

- **Batch endpointi** (`/allowed-work-code-categories`, `/details`) već koriste batch resolver (`translationsFor(locale)` jednom, pa mapa) — obrazac se zadržava i za nove entitete.
- **Eksporti** se danas generišu na frontendu (PDF preko `@react-pdf/renderer`, Excel/CSV preko `Blob`), pa locale bira frontend. Za obračunski list to znači: **frontend mora da pošalje `?locale=<employee.preferredLocale>`** kada dohvata podatke za PDF — ne svoj UI locale. Vidi §19, Faza 8.

---

## 14. Strategija korisničkog `preferred_locale`

### 14.1 Dva polja, dva značenja — već postoje i ostaju odvojena

| Polje | Znači | Ko menja |
|---|---|---|
| `user_preferences.language` | jezik **aplikacije** za ulogovanog korisnika | korisnik sam (`PATCH /api/user-preferences/me`) |
| `employees.preferred_locale` | jezik **dokumenata o zaposlenom** | administrator, na kartonu zaposlenog |

Ovo razdvajanje je već dokumentovano i obrazloženo (`2026-07-27-07` header: „employees are not users — most have no account at all"). **Ne spajati ih.** Nema potrebe za novim poljem — oba već postoje.

### 14.2 Šta treba popraviti

1. **Backfill i default** — migracija (§8): `'sr'` → `'sr-Latn'`, default `'sr-Latn'`, `CHECK`, `VARCHAR(35)`.
2. **Validacija u `UserPreferencesService`** — danas je nema uopšte (`:61-62` samo `trim()`). Dodati `AppLocales.isSupported()` → 400.
3. **`EmployeeService:345`** — zameniti `SUPPORTED.contains()` sa `AppLocales.isSupported()` (case-insensitive). Popravlja nekonzistentnost (B).
4. **`user_preferences.number_format`** (`'sr-RS'`) — **nigde se ne čita**. Ne graditi ništa na njemu; format se izvodi iz `language` (§11.5). Ostaviti kolonu, ili je označiti kao deprecated u komentaru.

### 14.3 Frontend inicijalizacija

```
1. localStorage 'marel-locale'         ← trenutna sesija, instant, bez mrežnog čekanja
2. GET /api/user-preferences/me → language   ← autoritet, primenjuje se po dolasku
3. navigator.language                   ← prvi login, pre nego što profil postoji
4. 'sr-Latn'
```

Redosled 1-pre-2 je namerno: aplikacija se renderuje odmah na poslednjem poznatom jeziku, pa se koriguje kad stigne profil. Alternativa (čekati profil) daje prazan ekran ili flash srpskog teksta.

`AuthContext` je pravo mesto za korak 2 — već dohvata korisnika pri startu.

### 14.4 Promena jezika

**Bez reload-a.** `i18n.changeLanguage()` je reaktivan kroz `react-i18next`. Uz to, u istom handleru:

```ts
i18n.changeLanguage(next);
dayjs.locale(dayjsLocale(next));
localStorage.setItem('marel-locale', next);
patchUserPreferences({ language: next });          // fire-and-forget
queryClient.invalidateQueries();                   // ← OBAVEZNO
```

**`invalidateQueries()` je ključan i lako se zaboravi:** `displayName` vrednosti iz baze su keširane u React Query po starom `Accept-Language`. Bez invalidacije UI labele se prevedu, a nazivi kategorija ostanu na starom jeziku — vrlo zbunjujuće.

**Alternativa (bolja):** ubaciti `i18n.language` u `queryKey` za sve upite koji vraćaju lokalizovane podatke. Čistije, ali dodiruje mnogo hook-ova. **Preporuka: `invalidateQueries()` u fazi 2, prelazak na `queryKey` po modulu u fazama 4–6.**

Language switcher: u `UserButton` meniju (postoji `components/UserButton/`), pored `ThemeSwitcher`-a — isti obrazac, isto mesto.

---

## 15. Plan migracije postojećih podataka

### 15.1 Ključna olakšica

**Kod Varijante A migracija postojećih srpskih naziva je — nikakva.** Nazivi ostaju gde jesu. Nema `INSERT … SELECT name` u translation tabele, nema rizika od delimičnog backfill-a, nema mogućnosti da neki red ostane bez osnovnog naziva.

Ovo je konkretan, merljiv razlog za A u ovom projektu, gde ne postoji migracioni framework sa rollback-om.

### 15.2 Šta migracija ipak radi

| Korak | Šta | Rizik |
|---|---|---|
| M1 | Dijagnostika: pobrojati sve `name`/`category_name`/`display_text` vrednosti u 8 šifrarnika i proveriti pismo | Nula (samo `SELECT`) |
| M2 | `employees.preferred_locale`: proširiti `CHECK` | Nizak |
| M3 | `user_preferences.language`: backfill `'sr'` → `'sr-Latn'`, default, `CHECK`, `VARCHAR(35)` | Nizak — jedini `UPDATE` nad postojećim podacima |
| M4 | Postojeće dve translation tabele: dodati `CHECK (locale IN …)` | Nizak; proveriti da nema `locale` van skupa (danas je samo `'en'`) |
| M5 | Nove translation tabele (§7) — prazne, bez backfill-a | Nula |
| M6 | Seed `en` prevoda gde su poznati; `sr-Cyrl` i `ru` **prazni** | Nula |
| M7 | `work_calendar_days.holiday_key` + backfill po mapi label→key | Srednji — vidi M7a |

**M7a — dijagnostika pre backfill-a `holiday_key`:** `label` može sadržati ručno unesene vrednosti i „Zamena za praznik (X)" oblik. Backfill mora:
- mapirati samo **tačna** poklapanja sa poznatim nazivima iz `SerbianHolidayCalculator`;
- za „Zamena za praznik (X)" izvući X i postaviti `SUBSTITUTE_FOR:<key>`;
- ostaviti `holiday_key = NULL` za sve što se ne poklopi — `label` ostaje i prikazuje se kao pre.

Nikad ne pogađati.

### 15.3 Dijagnostički korak M1 (obavezan, pre svega)

Projekat već ima presedan: `docs/business-rules/payroll-migration-diagnostics.sql`. Isti obrazac:

```sql
-- Postoji li ćirilica u podacima koji se smatraju latiničnim?
SELECT 'work_code_categories' AS t, id, category_no AS code, category_name AS name
FROM work_code_categories WHERE category_name ~ '[Ѐ-ӿ]'
UNION ALL SELECT 'payroll_adjustment_categories', id, code, name
FROM payroll_adjustment_categories WHERE name ~ '[Ѐ-ӿ]'
UNION ALL SELECT 'compensation_schemes', id, code, name
FROM compensation_schemes WHERE name ~ '[Ѐ-ӿ]'
-- … 8 tabela

-- Prazni ili beli nazivi
SELECT … WHERE name IS NULL OR length(trim(name)) = 0;

-- Duplikati naziva unutar iste tabele (indikator loše higijene, ne blokada)
SELECT name, count(*) FROM … GROUP BY name HAVING count(*) > 1;

-- Postojeće locale vrednosti van podržanog skupa
SELECT DISTINCT locale FROM work_code_category_translations
UNION SELECT DISTINCT locale FROM payroll_adjustment_category_translations
UNION SELECT DISTINCT preferred_locale FROM employees
UNION SELECT DISTINCT language FROM user_preferences;
```

Poslednji upit je najvažniji — otkriva sve što je ušlo u `user_preferences.language` dok validacije nije bilo.

### 15.4 Transliteracija latinica → ćirilica

**Preporuka: automatska transliteracija SE KORISTI, ali samo kao pomoćni alat, nikad kao izvor istine.**

Tvoj oprez je opravdan i evo konkretnih dokaza iz ovih podataka:

- `work_code_categories.category_no` — `J`, `D`, `PL`, `PLB`, `L3` — **kodovi, ne prevode se nikad**, ali se pojavljuju *unutar* naziva u UI kontekstu.
- Nazivi sadrže strane termine: „Galvanizacija", „Plastika – 1 ili 2 mašine".
- `app_settings.unit`: `RSD`, `min`, `%` — `RSD` se ne transliteruje.
- Nazivi šema: „Fiksni koeficijent".

Ništa od ovoga ne sme automatski u produkciju.

**Predlog: `sr-Cyrl` se NE seed-uje automatski.** Umesto toga:

1. Dijagnostički skript generiše **predlog** transliteracije kao `INSERT` naredbe u zaseban `.sql` fajl (nije migracija).
2. Osoba iz poslovnog dela ga pregleda i ispravi.
3. Pregledani fajl postaje migracija.

Fallback `sr-Cyrl → sr-Latn` garantuje da do tada ništa nije pokvareno — ćirilični korisnik vidi latinicu, što je i danas jedino što postoji.

### 15.5 Ruski

**Nikad ne izmišljati ruski prevod u migraciji.** Prazno je ispravno stanje; fallback ga pokriva. Ruski prevodi ulaze kroz administrativni ekran (§18) ili kroz posebnu migraciju kada ih neko ko govori ruski isporuči.

Isto pravilo već postoji u `2026-07-27-04` za engleski: seed-ovan je samo tamo gde postoji nedvosmislen ekvivalent, ostalo pada na fallback („Anything not listed here falls back to the Serbian name and is reported as still needing a translation").

---

## 16. Backend: servisi, repozitorijumi, DTO-i, mapperi

### 16.1 Novo

| Paket | Klasa | Uloga |
|---|---|---|
| `common/i18n` | `LocaleResolver` | `Accept-Language` + `?locale=` + user pref → normalizovan locale |
| `common/i18n` | `ResolvedLocaleArgumentResolver` | `@ResolvedLocale String locale` u kontrolerima |
| `common/i18n` | `TranslationResolverSupport<E,T>` | generički `translationsFor` / `displayName` |
| `common/i18n` | `TranslationRepositorySupport<T>` | `findAllByLocale`, `findByEntityAndLocale` |
| `common/i18n` | `TranslationAdminService<E,T>` | upsert / delete / validacija locale-a |
| `common/i18n` | `TranslationCompletenessService` | izveštaj o nedostajućim prevodima |
| `compensation_scheme` | `CompensationSchemeTranslation` + repo + resolver | pilot (Faza 4) |
| `payroll_time_adjustment` | `PayrollTimeAdjustmentCategoryTranslation` + repo + resolver | Faza 5 |
| `employee_payroll_value` | `EmployeePayrollValueDefinitionTranslation` + repo + resolver | Faza 5 |
| `app_settings` | `AppSettingTranslation` + repo + resolver | Faza 6 |
| `bonus` | `BonusCategoryTranslation` + repo + resolver | Faza 6 |
| `shift` | `ShiftTranslation` + repo + resolver | Faza 6 |
| `common/i18n` | `TranslationAdminController` | `/api/admin/translations/**` |

### 16.2 Menja se

| Fajl | Izmena | Breaking |
|---|---|---|
| `common/i18n/AppLocales.java` | +`sr-Cyrl`, +`ru`, `normalize()` proširen, +`isSupported()`, +`fallbackChain()` | Ne — `DEFAULT` i `ENGLISH` ostaju |
| `employee/EmployeeService.java:345` | `SUPPORTED.contains()` → `isSupported()` | Ne (samo popušta) |
| `user_preferences/UserPreferencesService.java:61` | + validacija `language` | **Da, blago** — do sada je prolazilo bilo šta |
| `work_code/WorkCodeCategoryNameResolver.java` | podklasa generičke baze, **isti javni ugovor** | Ne |
| `payroll_adjustment_category/PayrollAdjustmentCategoryNameResolver.java` | isto | Ne |
| `work_code/WorkCodeCategoryService.java:53` | `setEnglishName` → generički `setTranslation(id, locale, name)`; `setEnglishName` ostaje kao delegat | Ne |
| `work_code/WordCodeCategoryController.java` | `PUT …/translations/{locale}` (uz postojeći `/en`) | Ne (aditivno) |
| `work_code/dto/WorkCodeCategoryDto.java` | `nameEn` ostaje; **ne dodavati `nameSrCyrl`, `nameRu`** — admin podaci idu na admin endpoint | Ne |
| `payroll_adjustment_category/*` | `nameEn` ostaje kao deprecated alias; nova polja kroz admin endpoint | Ne |
| `payroll_run_item/PayrollRunItemService.java:469,600` | isti lanac, novi jezici | Ne |
| `outbox/NotificationFanoutService.java:214-248` | zadržati upis `title`/`message` (fallback), ali dodati render iz `type`+`payload` na čitanju | Ne |
| `common/GlobalExceptionHandler.java` | + `code`, `messageKey`, `params` uz postojeći `error` | Ne (aditivno) |
| `common/ConflictException.java` | + opcioni `code` + `params` | Ne |
| `work_calendar_day/SerbianHolidayCalculator.java` | vraća `(date, holidayKey, label)` umesto `(date, label)` | Ne (interno) |

### 16.3 DTO i domen — granica

Ostaje kako je već postavljeno:

```
Domen:      CompensationScheme { id, code, allowUnmappedCategories, allowsPerformanceBonus }
                                 ← nema displayName, nema translations

Read DTO:   CompensationSchemeResponse { id, code, name, displayName }
                                 ← displayName razrešen, name = master fallback

Admin DTO:  TranslationSetResponse { entityId, code, translations: { "sr-Latn": {...}, "en": {...}, … } }
```

Translation entiteti se **ne pojavljuju** u domenskoj logici. `WorkCategoryResolutionService`, `PayrollSchemeScopeService`, kalkulatori — nijedan ne sme da vidi `*Translation` klasu. Danas je tako i mora ostati; `NewCompensationSchemeIsDataOnlyIT` već čuva sličan invarijant za šeme.

---

## 17. Frontend: moduli i komponente koje treba menjati

### 17.1 Nova infrastruktura

```
src/ui/i18n/index.ts               # i18next init
src/ui/i18n/locales.ts             # SUPPORTED_LOCALES, mora se poklapati sa AppLocales
src/ui/i18n/format.ts              # useFormat() — zamena za 43 hardkodovana locale literala
src/ui/i18n/dayjs.ts               # sinhronizacija dayjs locale
src/ui/i18n/resources/**           # 17 namespace-a × 4 jezika
src/ui/components/LanguageSwitcher.tsx
src/ui/i18n/errorMessage.ts        # ApiError → t('errors:CODE', params) sa fallback na err.message
```

### 17.2 Izmene u postojećim ključnim fajlovima

| Fajl | Izmena | Faza |
|---|---|---|
| `src/ui/App.tsx` | `<I18nextProvider>` + `<DatesProvider settings={{ locale, firstDayOfWeek: 1 }}>` | 2 |
| `src/ui/lib/apiClient.ts:37-49` | `headers.set('Accept-Language', currentLocale())` — **jedna izmena za sve pozive** | 2 |
| `src/ui/app/AuthContext.tsx` | dohvat `user-preferences/me` → `i18n.changeLanguage` | 2 |
| `src/ui/lib/utils/dateUtils.ts` | ceo fajl → `useFormat()`; obrisati `monthNames` niz | 2 |
| `src/ui/Router.tsx` | `PageTitle` prima ključ, ne string; unifikovati mešavinu sr/en naslova | 7 |
| `src/ui/components/shell/AppShellLayout.tsx:170` | „Početna" → `t('nav:home')` | 7 |
| `src/ui/hooks/useBreadcrumbs.ts` | labele iz `t()` | 7 |
| `src/ui/features/common/notifications/notification.service.tsx` | prima ključeve, ne stringove (ili pozivaoci prevedu) | 7 |
| `src/ui/components/UserButton/*` | `<LanguageSwitcher/>` | 2 |

### 17.3 Mape kod→labela — najlakši prvi korak (Faza 2, ne 7)

Ovih 7 fajlova su već `Record<StableCode, string>` i pretvaraju se mehanički:

| Fajl | Postaje |
|---|---|
| `features/production-orders/ui/productionOrderStatusMeta.ts` | `t('common:status.productionOrder.' + code)` |
| `features/payroll-records/mappers/payrollStatusDisplay.ts` | `t('common:status.payroll.' + code)` |
| `features/employees/ui/EmployeeInfo.tsx:1137` | **duplikat gornje** — objediniti pri migraciji |
| `features/work-calendar/ui/workCalendarDayTypeMeta.ts` | `t('calendar:dayType.' + type)` |
| `features/components/google-auth/completeGoogleAuth.ts` | `t('errors:' + code)` |
| `features/payrolls/domain/adjustmentPolicy.ts:125` | `t('payroll:zeroReason.' + code)` |
| `features/employees/domain/payrollValueLabel.ts:13` | `t('common:yes')` / `t('common:no')` |

### 17.4 Redosled migracije modula (Faza 7)

Rangirano po *odnosu vrednosti i rizika*, ne po veličini:

| Red. | Modul | Fajlova sa tekstom | Zašto tim redom |
|---|---|---|---|
| 1 | `components/*` + `pages/*` | 21 | Deljeni; jednom prevedeni koriste svima. Nizak rizik. |
| 2 | `features/common` | 3 | Notifikacije, validacija — infrastruktura za ostale. |
| 3 | `features/payrolls` + `features/payroll-records` | 17 | Najveća poslovna vrednost (obračun je razlog postojanja i18n-a). |
| 4 | `features/employees` | 10 | Nosi `preferredLocale` UI. |
| 5 | `features/settings`, `work-calendar`, `bonuses`, `products` | 13 | Mali, izolovani. |
| 6 | `features/operations`, `records`, `production-orders`, `manufacturing-times`, `analytics` | 34 | Srednji. |
| 7 | **`features/shifts`** | **41** | **Poslednji.** Najveći (10 984 LOC), najsloženiji, i ima najviše testova sa srpskim asercijama. |
| — | `reference-v4` | 27 | **NE MIGRIRATI.** Dev-only galerija, ne ulazi u produkcijski build (`import.meta.env.DEV` guard u `Router.tsx`). Migracija bi bila 533 stringa bez ijedne koristi za korisnika. |

**Isključivanje `reference-v4` skida ~43% ukupnog stringovnog posla.** To je najveća pojedinačna ušteda u celom planu i treba je eksplicitno potvrditi (**D-8**).

---

## 18. Administracija prevoda

### 18.1 Da li je potrebna — da, ali ne odmah

Danas postoji minimalni oblik: `PUT /api/work-code-categories/{id}/translations/en` i `nameEn` polje na payroll adjustment kategorijama. Za `ru` i `sr-Cyrl` × 6 entiteta to više nije dovoljno.

### 18.2 Predloženi API

```
GET    /api/admin/translations/{entityType}/{id}
PUT    /api/admin/translations/{entityType}/{id}/{locale}
DELETE /api/admin/translations/{entityType}/{id}/{locale}
GET    /api/admin/translations/completeness
```

`entityType` je zatvoren enum (`WORK_CODE_CATEGORY`, `PAYROLL_ADJUSTMENT_CATEGORY`, `COMPENSATION_SCHEME`, …) — **ne slobodan string**, jer bi to bilo skriveno vraćanje polimorfnog modela koji je odbačen u §5.4.

```json
GET /api/admin/translations/PAYROLL_ADJUSTMENT_CATEGORY/1
{
  "id": 1,
  "code": "MEAL_ALLOWANCE",
  "defaultLocale": "sr-Latn",
  "translations": {
    "sr-Latn": { "name": "Topli obrok", "source": "MASTER" },
    "sr-Cyrl": { "name": null,          "source": "MISSING" },
    "en":      { "name": "Meal allowance", "source": "TRANSLATION" },
    "ru":      { "name": null,          "source": "MISSING" }
  }
}
```

`source` polje je važno: razlikuje „dolazi iz master kolone" od „ima svoj red" od „ne postoji". Bez njega ekran ne može znati da li upisuje u `master.name` ili u translation tabelu.

**Standardni read endpointi zadržavaju samo `displayName`** — ne vraćaju sve prevode.

### 18.3 Ekran

- **Tabovi po jeziku**, redosled iz `AppLocales.SUPPORTED` (zato `LinkedHashSet`).
- `sr-Latn` tab je označen **„Osnovni"**; upisuje u `master.name`; **ne može se obrisati**, samo izmeniti (`NOT NULL`).
- Ostali tabovi: dugme „Obriši prevod" → fallback.
- Badge po redu: „2/4" + `Tooltip` sa listom nedostajućih.
- Filter „Prikaži nepotpune".
- Prikaz fallback vrednosti kao **placeholder** u praznom polju (sivo), da administrator vidi šta korisnik trenutno vidi.
- Prava: nova `AppPermission` vrednost `TRANSLATIONS_ADMIN` u postojećem `config/security/RolePermissions`, `@PreAuthorize("@perm.has('TRANSLATIONS_ADMIN')")` — isti obrazac kao `USER_PREFERENCES_ADMIN`.

### 18.4 Audit i konkurencija

- **Audit:** automatski, kroz `audit_trigger_fn()` na translation tabeli. Ništa se ne piše ručno. `user_id` dolazi iz `AuditUserAspect`.
- **Konkurentna izmena:** za sada bez `@Version` (§6.2). Poslednji upis pobeđuje. Prihvatljivo za administratorski ekran sa malim timom; ako se pokaže kao problem, dodaje se `version` kolona — `GlobalExceptionHandler` već ima 409 handler.
- **Prevodi se menjaju nezavisno** od glavnog zapisa. Izmena prevoda **ne** pokreće recalc — prevod ne utiče na iznos. (Ovo eksplicitno napisati u dokumentaciji; neko će sigurno pitati.)

---

## 19. Fazni plan implementacije

Za svaku fazu: cilj, fajlovi, zavisnosti, rizici, testovi, kriterijum završetka, migracija, kompatibilnost.

---

### Faza 0 — Analiza *(ovaj dokument)*

**Cilj:** utvrđeno stanje, odabran model, popisan obim.
**Status:** ✅ završeno.
**Kriterijum završetka:** odgovori na `DECISIONS REQUIRED` (§22).

---

### Faza 1 — Locale osnova (backend)

**Cilj:** četiri lokala postoje, normalizuju se konzistentno, validiraju se na upisu. Popravljene tri nekonzistentnosti iz §1.4.

**Fajlovi:**
- `common/i18n/AppLocales.java` — proširiti
- `common/i18n/LocaleResolver.java` — NOVO
- `common/i18n/ResolvedLocaleArgumentResolver.java` — NOVO
- `employee/EmployeeService.java:345`
- `user_preferences/UserPreferencesService.java:61`
- migracija `2026-XX-XX-01-locale-foundation.sql`

**Migracija baze:** DA — `employees` CHECK, `user_preferences` backfill+default+CHECK+VARCHAR, CHECK na dve postojeće translation tabele.

**Zavisnosti:** nema.

**Rizici:**
- `user_preferences.language` backfill dira postojeće korisničke redove. Mitigacija: `UPDATE` je uslovan (`lower(language) IN ('sr','sr-rs','sr-latn-rs','')`), sve ostalo se ne dira; M1 dijagnostika prvo pokaže šta stvarno postoji.
- Validacija `language` je blago breaking za klijenta koji je slao smeće. Praktično: samo frontend zove taj endpoint i ne šalje ništa.

**Testovi:** `LocaleNormalizationTest` (unit, ~15 slučajeva iz tabele §4.3); `LocaleFoundationIT` (CHECK ograničenja odbijaju `'sr'`, `'de'`; backfill; case-insensitivnost).

**Kriterijum završetka:** `AppLocales.normalize()` prolazi celu tabelu §4.3; `SELECT DISTINCT language FROM user_preferences` daje samo podržane vrednosti; `./mvnw clean verify` zeleno.

**Backward compatible:** DA (sem validacije `language`).

---

### Faza 2 — Frontend i18n infrastruktura

**Cilj:** biblioteka radi, jezik se menja, formatiranje je locale-aware, `Accept-Language` se šalje. **Bez migracije ijednog UI teksta.**

**Fajlovi:** `package.json` (+`i18next`, `react-i18next`, `i18next-icu`); `src/ui/i18n/**` (NOVO); `App.tsx`; `lib/apiClient.ts:37-49`; `app/AuthContext.tsx`; `lib/utils/dateUtils.ts`; `components/LanguageSwitcher.tsx`; 7 mapa iz §17.3.

**Migracija baze:** NE.

**Zavisnosti:** Faza 1 (da bi `PATCH user-preferences {language}` prihvatao 4 vrednosti).

**Rizici:**
- **Electron + Vite bundling.** Statički `resources` import izbegava probleme sa `import.meta.glob` i `file://` protokolom u produkcijskom Electron build-u. Testirati `npm run dist:mac` **u ovoj fazi**, ne u fazi 9.
- `dayjs` locale i Mantine `DatesProvider` moraju da se menjaju **zajedno** sa i18next, inače datumi ostanu na srpskom.
- **`invalidateQueries()` posle promene jezika** — vidi §14.4.

**Testovi:** `i18n.test.ts` (init, `changeLanguage`, fallback za nepostojeći ključ, pluralizacija sr/ru/en); `format.test.ts` (novčani/datumski/procentni izlaz po lokalu); `apiClient.test.ts` (`Accept-Language` header prisutan i tačan).

**Kriterijum završetka:** language switcher menja `dayjs`, Mantine datume i `Accept-Language`; postojeći testovi (70 fajlova) i dalje zeleni; `npm run dist:mac` daje radnu aplikaciju.

**Backward compatible:** DA — 100% aditivno.

---

### Faza 3 — DB translation infrastruktura (generička)

**Cilj:** jedan resolver umesto N kopija; admin API; izveštaj o kompletnosti. **Bez nove translation tabele.**

**Fajlovi:** `common/i18n/TranslationResolverSupport`, `TranslationRepositorySupport`, `TranslationAdminService`, `TranslationCompletenessService`, `TranslationAdminController`; refaktor dva postojeća resolvera u podklasove; `config/security/AppPermission` + `RolePermissions` (`TRANSLATIONS_ADMIN`).

**Migracija baze:** NE.

**Zavisnosti:** Faza 1.

**Rizici:**
- **Refaktor dva postojeća resolvera dira payroll putanju.** Mitigacija: javni ugovor (`translationsFor`, `displayName` u 3 preopterećenja) se **ne menja**; `CategoryTranslationIT` (9 testova) mora proći **nepromenjen**; `PayrollGoldenSnapshotIT` mora dati identične iznose.
- Generizacija može slučajno uvesti per-red upit. Mitigacija: test koji broji upite (postoji presedan — `PayrollSchemeScopeBatchingIT`).

**Testovi:** `CategoryTranslationIT` nepromenjen i zelen; `PayrollGoldenSnapshotIT` nepromenjen; `TranslationAdminIT` (upsert, delete, nepodržan locale → 400, prazan naziv → 400, duplikat → 409, audit red nastaje); `TranslationBatchingIT` (N kategorija = 1 upit).

**Kriterijum završetka:** `./mvnw clean verify` zeleno, golden snapshot bit-identičan.

**Backward compatible:** DA.

---

### Faza 4 — Pilot: `compensation_schemes` (vertikalni presek)

**Cilj:** jedan entitet prođe ceo put — migracija, entitet, resolver, DTO, API, frontend, admin ekran, testovi.

**Zašto ovaj, a ne `payroll_adjustment_categories` kao u tvom predlogu:** payroll adjustment kategorije **već imaju** translation tabelu, pa nisu pilot. `compensation_schemes` je idealan: mali (2–3 reda), ima stabilan `code`, `name` mu je vidljiv na kartonu zaposlenog, i **nije na putanji izračunavanja iznosa** — `CompensationSchemeCodes` razrešava po kodu, nikad po nazivu. Greška u pilotu ne može pokvariti obračun.

**Fajlovi:** migracija `2026-XX-XX-02-compensation-scheme-translations.sql`; `compensation_scheme/CompensationSchemeTranslation.java` + repo + resolver; `CompensationSchemeService` (+`displayName`); DTO; frontend `CompensationSchemeSection.tsx`; admin ekran za prevode.

**Migracija baze:** DA — jedna nova tabela, bez backfill-a.

**Zavisnosti:** faze 1–3.

**Rizici:** nizak. Entitet je mali i van putanje izračunavanja.

**Testovi:** `CompensationSchemeTranslationIT` (prevod, fallback, `sr-Cyrl→sr-Latn`, `ru→sr-Latn`, cascade delete, unique `(id, lower(locale))`); frontend test da se `displayName` prikazuje i da fallback radi; `NewCompensationSchemeIsDataOnlyIT` **mora ostati zelen** (dodavanje šeme i dalje bez Java izmena).

**Kriterijum završetka:** šema ima naziv na sva 4 jezika, ekran ga menja, revizija beleži promenu, `EmployeeCompensationSchemeChangeIT` nepromenjen.

**Backward compatible:** DA.

---

### Faza 5 — Payroll entiteti

**Cilj:** `payroll_time_adjustment_categories`, `employee_payroll_value_definitions` dobijaju prevode; postojeće dve dobijaju `sr-Cyrl` i `ru`; obračunski list se prikazuje na sva 4 jezika.

**Fajlovi:** dve migracije; dva entiteta+repo+resolver; `PayrollRunItemService` (proširen lanac); `PayrollTimeAdjustmentService`; `EmployeePayrollValueService`; frontend `PayrollValuesSection.tsx`, `PayrollCategoriesTable.tsx`, `PayrollAdjustmentsSection.tsx`; **frontend šalje `?locale=`**.

**Migracija baze:** DA — dve nove tabele.

**Zavisnosti:** faze 1–4.

**Rizici:**
- **NAJVIŠI RIZIK U CELOM PLANU.** Ovo je payroll putanja. Mitigacija: `PayrollGoldenSnapshotIT` mora dati **bit-identične iznose u sva 4 lokala** — to je najvažniji jedan test celog projekta i mora biti napisan pre izmena.
- N+1 na obračunskom listu. Mitigacija: batch resolver + test koji broji upite.

**Testovi:** `PayrollGoldenSnapshotIT` parametrizovan po lokalu → identični iznosi; `PayrollLocalizationIT` (obračun na `ru` sa nedostajućim prevodima → svi nazivi neprazni); test da `employee.preferred_locale` ima prednost nad UI lokalom; `PayrollSchemeScopeBatchingIT` proširen.

**Kriterijum završetka:** obračunski list na `ru` daje iste brojeve kao na `sr-Latn`, sa razumljivim nazivima, bez N+1.

**Backward compatible:** DA.

---

### Faza 6 — Proizvodnja i operacije

**Cilj:** `app_settings`, `bonus_categories`, `shifts`, `work_calendar_days.holiday_key`, opciono `departments.code`.

**Fajlovi:** 3–4 migracije; 3 entiteta+repo+resolver; `SerbianHolidayCalculator` (vraća ključ); `WorkCalendarDayService`; frontend `AppSettingCard.tsx`, `WorkCalendar.tsx`, `BonusEligibilityGrid.tsx`.

**Migracija baze:** DA — 3 nove tabele + `holiday_key` kolona + backfill.

**Zavisnosti:** faze 1–3.

**Rizici:** `holiday_key` backfill mora ostaviti `NULL` gde se ne poklapa (§15.2 M7a); `departments.code` je promena šeme van i18n obima → **D-5**.

**Testovi:** `HolidayKeyBackfillIT` (poznati praznici mapirani, „Zamena za praznik (X)" parsiran, nepoznat `label` → `NULL` + `label` sačuvan); `AppSettingTranslationIT`.

**Kriterijum završetka:** ekran Parametri i kalendar rada na sva 4 jezika; nijedan postojeći `label` nije izgubljen.

**Backward compatible:** DA.

---

### Faza 7 — Migracija UI tekstova *(najveći posao)*

**Cilj:** ~700 različitih stringova (~1 125 pojavljivanja) prelazi u JSON, redosledom iz §17.4.

**Fajlovi:** praktično svi u `src/ui/features/**` i `src/ui/components/**`, **osim `reference-v4`**.

**Migracija baze:** NE.

**Zavisnosti:** Faza 2.

**Rizici:**
- **Testovi.** 35 test fajlova / 145+ asercija sa srpskim tekstom. Mitigacija: testovi se inicijalizuju sa `sr-Latn` i realnim resursima (ne `key`-mode), pa asercije nastave da rade. Ovo mora biti odlučeno **pre** početka faze 7 — vidi **D-6**.
- **Nema atomičnog reza.** Modul-po-modul, svaki modul je posebna PR/commit jedinica.
- Zaboravljeni stringovi. Mitigacija: ESLint pravilo (§21) uključeno **po modulu** kako se migrira, ne globalno na kraju.

**Testovi:** postojeći moraju ostati zeleni; `missingKeys.test.ts` (svi jezici imaju iste ključeve); po jedan „prebaci na `en` i proveri da nema srpskog" smoke test po modulu.

**Kriterijum završetka:** `grep -rE '[čćžšđČĆŽŠĐ]' src/ui --exclude-dir=reference-v4 --exclude='*.test.*'` daje samo komentare i JSON resurse.

**Backward compatible:** N/A (samo frontend).

---

### Faza 8 — Dokumenti, eksporti, notifikacije

**Cilj:** PDF obračun, Excel/CSV eksport i notifikacije poštuju locale.

**Fajlovi:** `features/payrolls/ui/PayrollPdf.tsx`, `features/manufacturing-times/ui/ManufacturingTimePdf.tsx`; `i18n/resources/*/documents.json`, `*/notifications.json`; `NotificationFanoutService` (render na čitanju); `assets/months/*.png` (§21 R-6).

**Migracija baze:** NE.

**Zavisnosti:** faze 2, 5, 7.

**Rizici:**
- **PDF font.** `PayrollPdf` registruje Arial embedan iz `payrollFonts`. **Ćirilica i sve `sr-Cyrl`/`ru` glifove treba proveriti** — ako embedani podskup nema ćirilicu, PDF će imati prazne kvadrate. Ovo je konkretan blocker koji se otkriva tek u testu, i mora se proveriti **rano**, ne u fazi 8. (Preporuka: proveriti u fazi 2, uz mali PoC PDF.)
- **PDF locale ≠ UI locale.** `PayrollPdf` mora tražiti podatke sa `?locale=<employee.preferredLocale>`, a statične labele uzeti iz istog lokala — ne iz `i18n.language`. Znači: privremeni `i18n.getFixedT(employeeLocale, 'documents')` umesto `useTranslation()`.
- `assets/months/*.png` — ako slike sadrže utisnute srpske nazive meseci, ne mogu se lokalizovati bez novih asseta.

**Testovi:** `PayrollPdf.test.tsx` (renderuje na `ru` sa `employee.preferredLocale='ru'` dok je UI na `sr-Latn`); test da ćirilični string ne proizvodi `.notdef` glifove; test render notifikacije iz `type`+`payload`.

**Kriterijum završetka:** obračun za ruskog radnika generisan iz srpskog UI-ja je na ruskom, sa čitljivom ćirilicom.

**Backward compatible:** DA.

---

### Faza 9 — Završne provere

**Cilj:** dijagnostika, performanse, pristupačnost, regresija, dokumentacija.

**Sadržaj:** izveštaj o nedostajućim prevodima; provera N+1 na listama i obračunu; provera da prelamanje teksta ne lomi layout (nemački nije u planu, ali ruski i engleski su duži od srpskog — konkretno „Обеспечение питанием" naspram „Topli obrok"); `lang` atribut na `<html>`; ažuriranje `docs/ARCHITECTURE_SNAPSHOT.md` i pisanje `docs/business-rules/i18n.md` (§20); regenerисanje `src/test/resources/db/baseline-schema.sql` i `reference-data.sql` (**obavezno posle svake faze sa migracijom**, inače `ddl-auto=validate` obara build — to je namerno).

---

## 20. Plan testiranja

### 20.1 Baza

| Test | Faza |
|---|---|
| `UNIQUE (entity_id, lower(locale))` odbija duplikat sa različitim slovima (`en` / `EN`) | 3 |
| FK postoji; `ON DELETE CASCADE` briše prevode sa entitetom | 4 |
| `CHECK (trim(name) ≠ '')` odbija prazan naziv | 3 |
| `CHECK (locale IN …)` odbija `'sr'`, `'de'`, `''` | 1 |
| `employees.preferred_locale` prihvata sva 4, odbija ostalo | 1 |
| `user_preferences.language` backfill: `'sr'` → `'sr-Latn'`, ništa drugo dirano | 1 |
| `audit_logs` red nastaje pri INSERT/UPDATE/DELETE prevoda, sa `record_id = id` | 3 |
| Idempotentnost: svaka nova migracija se primenjuje dvaput bez greške | svaka |

### 20.2 Backend unit

| Test | Faza |
|---|---|
| `normalize()` — cela tabela §4.3, ~15 slučajeva | 1 |
| `normalize(null/""/"   ")` → `sr-Latn` | 1 |
| `isSupported()` case-insensitive; `isSupported("de")` = false | 1 |
| `fallbackChain("sr-Cyrl")` = `[sr-Cyrl, sr-Latn]`; `fallbackChain("ru")` = `[ru, sr-Latn]` | 1 |
| `displayName` vraća prevod kad postoji | 3 |
| `displayName` fallback na master kad ne postoji | 3 |
| `displayName` nikad `null`, nikad prazan | 3 |
| **Poslovna logika ne zavisi od `displayName`** — statička provera da nijedan servis van `*NameResolver`/`*Mapper` ne poziva `getDisplayName()` | 3 |

### 20.3 Backend integration

| Test | Faza |
|---|---|
| `CategoryTranslationIT` — 9 postojećih, **nepromenjeni** | 3 |
| Admin endpoint vraća sve 4 lokala sa `source` (MASTER/TRANSLATION/MISSING) | 3 |
| Standardni endpoint vraća **samo** `displayName`, ne mapu prevoda | 3 |
| Nepodržan locale na **write** → 400; na **read** → tiho na default | 3 |
| **`PayrollGoldenSnapshotIT` parametrizovan po lokalu — identični iznosi** | 5 |
| Batching: N kategorija = 1 upit (obrazac `PayrollSchemeScopeBatchingIT`) | 3 |
| Sortiranje po `COALESCE(t.name, m.name)` sa join-om | 5 |
| Pretraga po lokalizovanom nazivu | 5 |
| `NewCompensationSchemeIsDataOnlyIT` ostaje zelen | 4 |
| `HolidayKeyBackfillIT` | 6 |

### 20.4 Frontend

| Test | Faza |
|---|---|
| `changeLanguage` menja tekst, `dayjs`, Mantine datume | 2 |
| Nedostajući JSON ključ → fallback, ne sirovi ključ | 2 |
| Pluralizacija: `sr` (1/2/5), `ru` (1/2/5/21), `en` (1/2) | 2 |
| Interpolacija sa brojevima i datumima | 2 |
| Formatiranje novca po lokalu (`1.234,50` / `1,234.50` / `1 234,50`) | 2 |
| `Accept-Language` prisutan na svakom pozivu | 2 |
| Postavka jezika se sačuva i preživi restart (`localStorage` + `user-preferences`) | 2 |
| Prikaz `displayName` iz backenda + fallback na `categoryName` | 5 |
| `queryClient.invalidateQueries()` posle promene jezika | 2 |
| **PDF na `employee.preferredLocale`, ne na UI lokalu** | 8 |
| **Ćirilica se renderuje u PDF-u bez `.notdef`** | 8 (proveriti u 2) |
| Svi jezici imaju identičan skup ključeva (`missingKeys.test.ts`) | 2 |
| RTL nije potreban, ali `dir` nije hardkodovan na `ltr` | 2 |

### 20.5 Migracioni

| Test | Faza |
|---|---|
| Produkcioni podaci ostaju dostupni posle svake migracije | svaka |
| Stari API oblik nastavlja da radi tokom prelaza | svaka |
| **Nijedan zapis nije bez osnovnog prikaznog naziva** (`master.name NOT NULL` to garantuje — test to i dokazuje) | 1 |
| Rehearsal nad klonom dev baze pre svake primene (`createdb marel_rehearsal`, obrazac iz `IMPLEMENTATION-STATUS.md` §7) | svaka |

---

## 21. Rizici i otvorene odluke

| # | Rizik | Verovatnoća | Uticaj | Mitigacija |
|---|---|---|---|---|
| R-1 | **PDF font nema ćirilicu** → prazni kvadrati u `sr-Cyrl`/`ru` obračunu | Srednja | **Visok** — obračunski list je razlog postojanja funkcije | Proveriti embedani Arial podskup u `payrollFonts` **u fazi 2**, ne u fazi 8. Ako nema — nabaviti font sa ćirilicom (npr. DejaVu/Noto) i re-embedovati. |
| R-2 | Faza 7 ruši 145+ test asercija | Visoka | Srednji | Testovi se inicijalizuju sa `sr-Latn` + realni resursi (D-6). |
| R-3 | N+1 na obračunskom listu posle dodavanja resolvera | Srednja | Visok | Batch-only ugovor resolvera + test koji broji upite. |
| R-4 | `ddl-auto=update` menja bazu na koju pokazuje pokrenuta aplikacija | Srednja | Visok | **Već zabeležen rizik projekta** (`IMPLEMENTATION-STATUS.md` §6.1 — desilo se jednom). Zaustaviti aplikaciju pre dodavanja entiteta. |
| R-5 | Zaboravljen `baseline-schema.sql` posle migracije | Visoka | Nizak | `ddl-auto=validate` u test profilu obara build — **to je namerno** i dovoljno. |
| R-6 | `assets/months/*.png` sadrže utisnute srpske nazive | Nepoznata | Nizak | Otvoriti slike i proveriti pre faze 8. Ako sadrže — potrebni novi asseti ili tekstualna zamena. |
| R-7 | Ruski prevodi ne postoje i niko ih ne isporučuje | Visoka | Nizak | Fallback pokriva. Prazno je ispravno stanje; ne izmišljati. |
| R-8 | Transliteracija latinica→ćirilica pokvari kodove/skraćenice | Visoka ako se automatizuje | Srednji | **Ne automatizovati.** Generisati predlog za ljudski pregled (§15.4). |
| R-9 | `?locale=` na payroll putanji promeni iznos | Vrlo niska | **Kritičan** | `PayrollGoldenSnapshotIT` po lokalu. Već je pravilo (`…localization.md` §11), ali ga treba i testirati. |
| R-10 | Duži tekstovi (`ru`, `en`) lome layout u gustim tabelama | Srednja | Nizak | Vizuelna provera u fazi 9; tabele već imaju `mantine-react-table` sa resize-om. |
| R-11 | Nema migracionog frameworka — ručni SQL, ručni rollback | — | Srednji | **Postojeći rizik projekta**, ne uvodi ga i18n. Rehearsal nad klonom + idempotentnost su postojeća praksa i dovoljni. |

---

## 22. Dokumentacija i CI provere

### 22.1 Nov dokument: `docs/business-rules/i18n.md`

Mora definisati (odgovara tvom §26):

1. **Kada frontend JSON, a kada DB translation tabela** — jedno pravilo: *„Ako administrator može da promeni tekst kroz aplikaciju, on je u bazi. Ako ga menja programer, on je u JSON-u."*
2. Imenovanje translation tabela: `<master_table_singular>_translations`.
3. Imenovanje locale vrednosti: BCP 47, kanonski oblik, tabela §4.3.
4. Kako se dodaje novi jezik (kontrolna lista: `AppLocales` → 3+ `CHECK` migracije → JSON resursi × 17 → `dayjs` locale → `Intl` mapiranje → PDF font).
5. Kako se dodaje novo prevodivo polje / novi prevodivi entitet (šablon §6.1).
6. Fallback lanac (§9.1) i zašto `ru` ne ide preko `en`.
7. Konvencija ključeva (§11.3) **uključujući pravilo o padežima** (§11.4) — to je najlakše prekršiti.
8. **Šta nikad nije prevodivo:** `code`, `category_no`, `setting_key`, statusni enumi, `impact_code`, `calculation_key`, `section_code`, audit zapisi, slobodan korisnički tekst.
9. Kako se testira nov prevod.
10. Kako se prijavljuju nedostajući prevodi.

Ažurirati i `docs/ARCHITECTURE_SNAPSHOT.md` (i18n stack) i oba `CLAUDE.md`.

### 22.2 CI / lint provere

| Provera | Kako | Faza |
|---|---|---|
| Svi jezici imaju identične ključeve | Vitest test koji rekurzivno poredi skupove ključeva; ispisuje razliku | 2 |
| Nema duplih ključeva u JSON-u | isti test (JSON parser već odbija, ali provera po namespace-u hvata premeštanja) | 2 |
| Nema nepoznatog locale-a u kodu | grep/ESLint na literale `'sr'`/`'sr-RS'`/`'en-US'` van `i18n/` | 2 |
| **Nema hardkodovanog korisničkog teksta u novim komponentama** | `eslint-plugin-i18next` `no-literal-string`, uključivan **po direktorijumu** kako faza 7 napreduje | 7 |
| Svi sistemski šifrarnici imaju neprazan `master.name` | IT test | 1 |
| TypeScript zna ključeve | `react-i18next` module augmentation → nepostojeći ključ je build greška | 2 |

---

## 23. Kompatibilnost i rollout

### 23.1 Prelazni period

Tvoj traženi postepeni pristup je **automatski zadovoljen Varijantom A** — stara `name` polja se **nikad ne uklanjaju**, pa nema perioda dual-write-a, nema izvora istine koji se pomera, nema provere konzistentnosti.

```
1. Dodaju se translation tabele                        (faze 3–6)   ← aditivno
2. Postojeća `name` polja OSTAJU TRAJNO                             ← nije privremeno
3. Backfill NIJE POTREBAN                                           ← name JE sr-Latn
4. Backend čita prevod, fallback na `name`             (već radi)
5. Frontend prelazi postepeno, modul po modul          (faza 7)
6. `name` se NE deprecira                                           ← ostaje kao default+fallback
7. `name` se NIKAD ne uklanja
```

**Dual-write se ne preporučuje i nije potreban.** Nema dva mesta koja drže istu vrednost: `master.name` drži `sr-Latn`, translation tabela drži ostalo. Skupovi su disjunktni. To je jedina strukturna prednost A nad B koja se ne može nadoknaditi disciplinom.

### 23.2 Breaking vs backward-compatible

| Izmena | Klasifikacija |
|---|---|
| Nove translation tabele | ✅ backward-compatible |
| `AppLocales` + 2 lokala | ✅ |
| `Accept-Language` header sa frontenda | ✅ (backend ignoriše dok ga ne implementira) |
| `?locale=` na novim endpointima | ✅ (opciono) |
| `displayName` u odgovorima | ✅ (aditivno polje; `name`/`categoryName` ostaju) |
| `code`/`messageKey`/`params` u telu greške | ✅ (`error` ostaje) |
| `Content-Language` / `X-Translation-Fallback-Used` header | ✅ |
| `employees.preferred_locale` CHECK proširen | ✅ (proširenje skupa) |
| `work_calendar_days.holiday_key` | ✅ (nullable) |
| `user_preferences.language` **backfill** `'sr'`→`'sr-Latn'` | ⚠️ **blago breaking** — vrednost se menja, ali ponašanje ostaje isto (`'sr'` se ionako nigde nije čitao) |
| `user_preferences.language` **validacija** | ⚠️ **blago breaking** — do sada je prolazilo bilo šta. Jedini pozivalac je frontend. |
| `EmployeeService` case-insensitive validacija | ✅ (samo popušta) |
| Refaktor dva resolvera | ✅ (javni ugovor nepromenjen) |
| Migracija UI tekstova | ⚠️ **breaking za frontend testove**, ne za korisnike |
| Uklanjanje `name` kolona | ❌ **ne radi se** |

**Nijedna izmena ne menja nijedan iznos, nijedan status i nijedan poslovni identifikator.**

---

## 24. Preporučeni redosled implementacije

```
Faza 1  Locale osnova (backend)          — mala, popravlja 3 postojeća buga     ← POČETI OVDE
Faza 2  Frontend i18n infrastruktura     — srednja, 100% aditivna
        └─ u istom potezu: PoC ćirilice u PDF-u (R-1) i test dist:mac
Faza 3  Generička DB translation infra   — srednja, refaktor bez nove tabele
Faza 4  Pilot: compensation_schemes      — mala, vertikalni presek
Faza 5  Payroll entiteti                 — velika, NAJVIŠI RIZIK, golden snapshot obavezan
Faza 6  Proizvodnja i operacije          — srednja
Faza 7  Migracija UI tekstova            — NAJVEĆA, modul po modul (§17.4)
Faza 8  Dokumenti, eksporti, notifikacije
Faza 9  Završne provere i dokumentacija
```

Faze 1 i 2 su nezavisne i mogu paralelno (različiti repozitorijumi).
Faza 3 zavisi samo od 1.
Faza 6 zavisi od 3, ne od 4 i 5 — može paralelno sa 5 ako ima kapaciteta.
Faza 7 zavisi samo od 2 — može početi čim je 2 gotova i teći paralelno sa 4–6.

**Najkraći put do vidljive vrednosti:** faze 1 → 2 → 3 → 5. Time obračunski list radi na 4 jezika, što je poslovni razlog celog poduhvata. Faza 7 je najveći trošak i najmanja poslovna hitnost — administratori su govornici srpskog.

---

## DECISIONS REQUIRED BEFORE IMPLEMENTATION

Samo odluke koje se **ne mogu** pouzdano izvesti iz postojećeg sistema.

**D-1. Da li je `sr-Latn` zaista podrazumevani, ili poslovanje želi ćirilicu kao podrazumevanu?**
Tehnički je `sr-Latn` već default i svi podaci su na latinici. Ali ako je zvanični jezik dokumenata ćirilica, to menja koji locale je „master" i pretvara migraciju iz nule redova u pun backfill. **Ovo je jedina odluka koja može promeniti ceo plan.**

**D-2. Da li je ruski stvarno potreban, i ko isporučuje prevode?**
Postoji `FOREIGN_FIXED_COEFFICIENT` šema i `employees.preferred_locale`, što sugeriše strane radnike — ali ni jedan ruski string nigde ne postoji. Ako nema ko da prevede, `ru` postaje trajno prazan skup koji uvek pada na srpski, i vredi ga odložiti.

**D-3. Da li administratori aplikacije stvarno menjaju jezik, ili je višejezičnost samo za dokumente zaposlenih?**
Ako je samo za dokumente, **faza 7 (~700 stringova, najveći trošak plana) otpada u potpunosti** i posao se svodi na faze 1, 3, 5, 8. Ovo je odluka sa najvećim uticajem na obim.

**D-4. `CHECK (locale IN …)` u svakoj tabeli, ili referentna tabela `supported_locales` sa FK?**
CHECK je konzistentan sa postojećim `employees.preferred_locale`, ali dodavanje jezika postaje migracija. Referentna tabela je fleksibilnija ali uvodi FK na svaki prevod.

**D-5. Da li se `departments` dodaje `code` kolona?**
Jedini kandidat bez stabilnog koda. Alternativa: izuzeti odeljenja iz prevoda. Ovo je promena šeme van striktnog i18n obima.

**D-6. Kako se frontend testovi ponašaju pod i18n-om?**
(a) `sr-Latn` + realni resursi → 145 asercija nastavlja da radi, ali testovi zavise od JSON sadržaja;
(b) key-mode (`t()` vraća ključ) → čistije, ali sve asercije treba prepisati.
Preporuka je (a), ali odluka pripada onome ko održava testove.

**D-7. Da li `description` polja na `payroll_time_adjustment_categories` i `employee_payroll_value_definitions` treba da budu korisnički vidljiva?**
Danas su na engleskom i pisana za programera. Ako su namenjena korisniku — treba ih prepisati na srpski i prevoditi. Ako nisu — treba ih premestiti u `COMMENT ON COLUMN`.

**D-8. Da li se `reference-v4` galerija prevodi?**
Dev-only, 533 stringa, ne ulazi u produkcijski build. Preporuka je NE, ali ako se koristi kao živa dokumentacija dizajna za neengleske saradnike, možda treba.

---

## RECOMMENDED DEFAULT DECISIONS

Ako nema odgovora, kreni sa ovim — nijedno ne zatvara vrata drugačijoj odluci kasnije.

| # | Preporučeni podrazumevani izbor | Zašto je bezbedan |
|---|---|---|
| **D-1** | **`sr-Latn` ostaje podrazumevani.** | Već jeste `AppLocales.DEFAULT`; svi podaci su na latinici; `dayjs/locale/sr` je latinični. Promena kasnije je jedna konstanta + jedna migracija koja premesti `name` u eksplicitan `sr-Latn` red. |
| **D-2** | **`ru` se uvodi u `AppLocales` i `CHECK`, ali se NE seed-uje ni jedan ruski prevod.** | Infrastruktura košta ~nula ako se radi zajedno sa `sr-Cyrl`; prevodi mogu doći kasnije bez ijedne izmene koda. Fallback pokriva prazno stanje. |
| **D-3** | **Pretpostaviti da su potrebna oba** — i UI i dokumenti — ali **redosled staviti tako da dokumenti idu prvi** (faze 1→2→3→5→8), a faza 7 poslednja. | Ako se ispostavi da UI nije potreban, faza 7 se jednostavno ne radi i ništa nije bačeno. Obrnut redosled bi značio da je najveći trošak plaćen pre nego što se sazna da li treba. |
| **D-4** | **`CHECK (locale IN ('sr-Latn','sr-Cyrl','en','ru'))`** na svim translation tabelama. | Konzistentno sa postojećim `chk_employees_preferred_locale`. Dodavanje petog jezika je ionako projekat, ne konfiguracija. Prelazak na referentnu tabelu kasnije je jedna migracija. |
| **D-5** | **Odeljenja se za sada NE prevode.** Bez izmene šeme. | Nizak prioritet; izbegava promenu šeme van i18n obima u fazi 6. Dodavanje `code` kolone kasnije je aditivna migracija. |
| **D-6** | **(a) `sr-Latn` + realni resursi u testovima.** | 145 postojećih asercija nastavlja da radi; migracija po modulu ne obara ceo suite odjednom. Prelazak na key-mode kasnije je izmena jednog test setup fajla. |
| **D-7** | **Ne prevoditi ih; ostaviti kao interne.** Dodati `description` kolonu **samo** u `payroll_time_adjustment_category_translations`, gde je opis stvarno korisnički. | Ne gradi se infrastruktura za tekst koji nijedan korisnik ne vidi. Ako se ispostavi da treba — dodavanje kolone je aditivna migracija. |
| **D-8** | **Ne prevoditi `reference-v4`.** | Dev-only, iza `import.meta.env.DEV` guard-a u `Router.tsx`, ne ulazi u produkcijski bundle. Skida ~43% stringovnog posla za nula gubitka za korisnika. |

**Dodatno, nezavisno od svih odluka — uraditi odmah, jer su to postojeći bugovi:**

1. `user_preferences.language`: backfill `'sr'` → `'sr-Latn'`, default, `CHECK`, validacija u servisu.
2. `EmployeeService:345`: case-insensitive validacija locale-a.
3. Proveriti da embedani Arial u `payrollFonts` sadrži ćirilicu (**R-1**) — pre nego što se bilo šta drugo počne, jer negativan nalaz menja faze 2 i 8.
