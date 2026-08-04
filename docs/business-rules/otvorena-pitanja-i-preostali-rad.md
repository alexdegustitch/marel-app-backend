# Otvorena pitanja i preostali rad

Stanje na dan 2026-08-04, posle završetka faze 7 (brisanje ogledalskih kolona).
`payroll_run_items` je sa 66 na 41 kolonu. 254 backend testa, 956 frontend, 0
padova.

Šest stavki je ostalo. **Tri traže odgovor klijenta** — bez njih se ne zna šta je
tačno, pa ih ne mogu odlučiti ja. **Tri su moj posao** i ne čekaju nikoga.

---

## A. Traži odluku klijenta

### A1 · OPEN-12 — da li telefon tekućeg meseca i „isplaćeno u prethodnom periodu" umanjuju zaradu?

**Šta je danas.** Nijedna od te dve stavke ne ulazi ni u jedan zbir:

| Stavka | Sekcija | Uticaj | Ulazi u zbir? |
|---|---|---|---|
| `PHONE_CURRENT_MONTH` | `PHONE` | `DEDUCTION_MINUS` | **ne** |
| `PHONE_PREVIOUS_MONTH` | `SETTLEMENTS` | `DEDUCTION_MINUS` | da |
| `PAID_PREVIOUS_PERIOD` | `SETTLEMENTS_SUM` | `PAYMENT_MINUS` | **ne** |
| `PAID_PART_1`, `PAID_PART_2`, `INSTALLMENT` | `SETTLEMENTS` | — | da |

Zbir isplaćenog (`previouslyPaidAmount`) filtrira po **sekciji**, doslovno po
tekstu `'SETTLEMENTS'`. Zato prve dve ispadaju: jedna je u sekciji `PHONE`, druga
u `SETTLEMENTS_SUM`.

**Zašto to smeta.** Faza 4 je prebacila **zaradu** sa sekcija na uticajne kodove
(`impact_code`) i dokazano nije pomerila ni dinar. Isplate su ostale na sekcijama
i ne mogu da pređu dok se ovo ne odgovori — jer bi prelazak automatski počeo da
odbija obe stavke gore.

**Koliko novca je u pitanju, u ovoj bazi:**

- `PHONE_CURRENT_MONTH` — 5 stavki, ukupno **22.200,00**
- `PAID_PREVIOUS_PERIOD` — 6 stavki, ukupno **23.000,00**

**Pitanja za klijenta, doslovno:**

> 1. Telefon za tekući mesec — da li se odbija od zarade **tog** meseca, ili tek
>    **sledećeg** (kao „telefon za prethodni mesec")?
>    *Sistem danas radi ovo drugo: iznos se prenosi u sledeći mesec i tamo se
>    odbija. Ako je odgovor „tog meseca", onda se prenos u sledeći mesec mora
>    ukinuti — inače bi se isti telefon naplatio dvaput.*
>
> 2. „Isplaćeno u prethodnom obračunskom periodu" — da li je to **informativna**
>    stavka na obračunskom listu, ili **stvarna** isplata koja umanjuje ono što
>    ostaje za isplatu?
>    *Sistem danas radi ovo prvo: prethodno stanje već nosi
>    `previous_net_payable_amount`, pa bi brojanje ove stavke bilo dvostruko.*

**Šta radim kad dobijem odgovor.** Ako su odgovori „sledećeg meseca" i
„informativna" — potvrđuje se današnje ponašanje, prebacujem isplate na uticajne
kodove i sekcije prestaju da odlučuju o novcu. Ako je bilo koji drugačiji, menja
se ono što ljudi dobijaju na ruke, pa ide zasebna migracija sa preračunom i
pisanim tragom.

---

### A2 · OPEN-15 — istorijski meseci i prevoz po danu

**Šta je danas.**

- 98 od 135 aktivnih radnika je na režimu **prevoz po danu**.
- Cena po danu je **jedno podešavanje za celu firmu**: `transport_allowance_per_day`
  = 200,00, važi od 2020-03-22, bez kraja (OPEN-11: nema cene po radniku).
- 37 radnika je na **fiksnom mesečnom** prevozu, i **oni imaju datiranu istoriju**
  po radniku (`TRANSPORT_FIXED_MONTHLY`), pa se za njih zna od kada važi.

**Problem.** Režim „po danu" ne čita nijednu vrednost vezanu za radnika, pa ne
postoji datum od kog počinje. Svaki mesec u kom je radnik radio dobija prevoz —
uključujući istorijske — čim ga neko sledeći put otvori, jer se stavke u statusu
DRAFT preračunavaju pri čitanju.

**Razmera:** **322** nearhivirane stavke pre 2026. godine. Nijedna nije
zaključana — u celoj bazi nema nijedne zaključane stavke.

**Ovo nije problem dok je posao u grani.** Postaje problem na dan prvog puštanja u
rad. Tada su tri izlaza:

1. **Zaključati mesece do preseka.** Operacija zaključavanja postoji
   (`POST /api/payroll-run-items/{id}/lock`, OPEN-13). Zaključana stavka se više
   ne preračunava, pa istorija ostaje kakva je isplaćena. Traži odluku klijenta:
   **do kog meseca zaključujemo?**
2. **Prihvatiti da istorijski meseci dobiju prevoz.** Legitimno ako te mesece
   ionako niko ne otvara i ne štampa.
3. **Dati i režimu „po danu" datiranu istoriju po radniku**, kao što fiksni već
   ima. To je trajno rešenje i moj posao — ali traži od klijenta **od kog datuma
   svaki radnik dobija prevoz po danu**, inače nemam šta da upišem.

**Pitanje za klijenta, doslovno:**

> Prevoz po danu (200,00 dinara po danu) — od kog meseca ga računamo? Da li stari
> meseci, koji su već isplaćeni po starom Excel postupku, treba da ostanu
> netaknuti?

---

### A3 · OPEN-16 — koje sate bonus poredi sa pragom (samo potvrda)

**Ovo je već odlučeno u kodu**, sa napisanim obrazloženjem, i navodim ga da bi
klijent potvrdio ili demantovao — ne da bi se čekalo.

`MonthlyBonusCalculator` meri „koliko je sati radio" kao **`total_payroll_minutes`**
— odrađeni minuti **plus ručne korekcije**. Ranije je čitao `total_work_minutes`,
bez korekcija, pa je administrator koji doda zaboravljenu smenu video da sati
rastu na ekranu a bonus stoji.

Dve cifre se razlikuju samo tamo gde je neko ručno korigovao vreme — u ovoj bazi
2 stavke.

**Pitanje za klijenta:**

> Kad se radniku ručno doda zaboravljena smena, da li ti sati ulaze u uslov za
> mesečni bonus? *(Sistem danas kaže: da — bonus se odlučuje po satima za koje je
> radnik plaćen.)*

---

## B. Moj posao, ne čeka nikoga

### B1 · Audit trag na `payroll_adjustments` se guši u sopstvenom preračunu

**Šta je nađeno.** `trg_audit_logs_payroll_adjustments` je običan
`AFTER INSERT OR UPDATE OR DELETE` bez `WHEN` klauzule, pa **svaki preračun upiše
pun diff po liniji**.

- **33.472** update zapisa ukupno
- **20.954** od njih ne diraju ništa osim `system_*`, `calculated_at` i
  `calculation_inputs` — čist preračun
- stvarnih ljudskih odluka je oko **trideset**
- i broj raste **kad god neko otvori obračun**, jer je čitanje ustajale stavke
  upis

Posledica: spor oko obračuna za šest meseci znači traženje tih trideset odluka u
trideset hiljada zapisa. Trag koji se ne može pročitati je isto što i trag koji
ne postoji.

**Zašto se ne rešava `WHEN` klauzulom.** Iz istog razloga koji je zapisan u
`2026-09-03-01` za „poslednju aktivnost": izmena i preračun koji ona pokreće
završe u **istom** UPDATE-u na istom redu, pa nijedan test nad kolonama ne razdvaja
to dvoje. Klauzula dovoljno uska da izbaci šum izbacila bi i odluku — a izgubljena
odluka je gora od hiljadu suvišnih zapisa.

**Šta se traži.** Isto što je uspelo za aktivnost: **beležiti odluke eksplicitno,
kod pozivaoca.** Konkretno:

1. Zapis odluke se piše u `PayrollRunItemService`, na mestima gde odluka i nastaje
   (`applyAdjustmentPatch`, patch stope, zaključavanje) — ne u trigeru.
2. `payroll_adjustments` prestaje da se revidira po redu; ostaje eventualno
   INSERT/DELETE, gde nema šuma.
3. `PayrollAuditReconstructionIT` je **već napisan** i čuva ovo: pita poslovno
   pitanje umesto da imenuje kolonu, pa pukne ako neka odluka prestane da se
   beleži bilo gde. Test koji danas pribija defekt („churn postoji") tada mora
   svesno da se izmeni — što je i poenta.

Procena: srednje veliki posao, bez odluke klijenta, sa postojećim testom kao
zaštitom.

---

### B2 · Test backfill SQL-a iz faze 2

**Šta se traži.** Test koji pusti backfill SQL iz faze 2 nad **zasejanim**
payroll stavkama i proveri da je sažimanje dalo tačne granice perioda
(`valid_from` / `valid_until` po radniku).

**Zašto to nije već pokriveno.** Migracija nosi svoj `DO $$` blok koji je obara
ako ijedna stavka prestane da se razrešava na sopstvenu sistemsku stopu. To je
provera nad podacima koji su tu zatečeni — nije isto što i test koji sam napravi
poznat ulaz i proveri poznat izlaz. Ako sutra neko promeni backfill, `DO $$` blok
će i dalje proći nad praznom bazom.

Mali posao, čisto testovi.

---

### B3 · Aktivacione kapije za kategorije i načine obračuna

**Šta plan traži** (životni ciklus iz faze 5):

- nova **kategorija** se pravi neaktivna → dobije pravilo za **svaki aktivan**
  način obračuna → tek onda može da se aktivira;
- nov **način obračuna** se pravi neaktivan → dobije pravilo za **svaku aktivnu**
  kategoriju → tek onda može da se aktivira i dodeli radniku.

**Šta je danas.** Ništa ne sprečava aktivaciju sa rupom u matrici. Pravilo jeste
sprovedeno tamo gde mora — migracija odbija nepotpunu matricu, a razrešavač baca
grešku u trenutku obračuna — i **od danas** `PayrollConfigurationValidationService`
prijavi rupu unapred, za celu fabriku odjednom. Ali prijava je posle čina: neko
i dalje može da aktivira kategoriju u utorak i sazna u petak.

**Šta se traži:** provera u servisima kategorije i načina obračuna koja odbija
aktivaciju dok postoji ijedno nedostajuće pravilo, sa porukom koja **nabroji** šta
nedostaje.

**Jedina odluka koja tu postoji** — i mogu da je donesem sam ako klijent nema stav:
da li aktivacija **da se odbije**, ili samo **upozori**. Tabela rizika (R6) je
upravo za ovo predviđala „servis prijavljuje umesto da baca", da rutinski
administratorski posao ne bi bio blokiran. Moj predlog: **odbiti**, jer je alternativa
obračun koji pukne radniku pod rukom, a poruka tačno kaže šta da se popuni.

---

## Šta ne traži ništa

- **`hourly_rate` / `_system` / `_overridden` ostaju.** Nisu ogledalo:
  `hourly_rate_system` je snimak onoga što je istorija govorila **u trenutku kad je
  mesec računat**, a `hourly_rate` je stvarna stopa te stavke, koja se sme
  promeniti za jedan mesec. To je auditabilnost, ne dvostruko knjigovodstvo.
- **560 starih redova „poslednje aktivnosti".** Većina je lažna, ali se prava od
  lažne ne razlikuje — pravi upis i lenji preračun su napisali identičan red.
  Brisanje svih bacilo bi i prave. Košta samo jedan zastareo datum, koji prva
  stvarna izmena prepiše.
- **Preklapanje perioda načina obračuna.** Baza ga ne dozvoljava
  (`ex_ecsh_no_overlap`, EXCLUDE ograničenje), i to je provereno testom.
