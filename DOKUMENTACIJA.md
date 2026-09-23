# MojSafe — dokumentacija

Android aplikacija za sigurno čuvanje osobnih podataka (lozinke, kartice, PIN-ovi,
bilješke), nastala kao zamjena za staru aplikaciju Handy Safe. Napisana od nule u
Kotlinu (Jetpack Compose), bez ijedne linije originalnog Handy Safe koda — stari
format baze je samo pročitan (uz dozvolu vlasnika podataka, radi jednokratne
migracije) i podaci su izvezeni u čitljiv oblik.

Ciljani sustav: Android 16 (API 36), isključivo 64-bitne arhitekture
(`arm64-v8a`, `x86_64`).

---

## 1. Pregled značajki

- **Zaključavanje lozinkom** — aplikacija se zaključava pri svakom pravom izlasku
  u pozadinu (home tipka, prebacivanje aplikacije, gašenje ekrana) i traži
  lozinku ponovno.
- **Mape i kartice** — hijerarhijska organizacija podataka (mape mogu sadržavati
  podmape i kartice, neograničena dubina).
- **Puno uređivanje** — dodavanje, izmjena, brisanje i **premještanje** mapa i
  kartica na bilo kojoj razini.
- **Polja unutar kartice** — proizvoljan broj parova naziv/vrijednost po
  kartici, s mogućnošću mijenjanja **redoslijeda** polja (strelice gore/dolje).
- **Tamna/svijetla tema** — ručni izbor (Light / Dark / Follow system).
- **Šifrirani backup/restore** — izvoz cijele baze u jednu vanjsku, lozinkom
  zaštićenu datoteku (`.mojsafe`) koja se može spremiti bilo gdje (lokalno,
  Google Drive, Dropbox — što god je instalirano na uređaju) i kasnije vratiti.
- **Prilagodljiva ikona i izgled** — ikona aplikacije i logo na ekranu za
  lozinku mogu se zamijeniti bilo kojom slikom.
- **Dijagnostika bez pristupa uređaju** — hvatanje neuhvaćenih grešaka (crash)
  *i* zamrzavanja aplikacije (ANR), spremljeno u čitljiv tekstualni fajl na
  uređaju, tako da se problem može opisati/kopirati i poslati na popravak bez
  fizičkog pristupa telefonu.

---

## 2. Sigurnosna arhitektura

MojSafe koristi **dva odvojena sloja enkripcije**, namjerno neovisna jedan o
drugom:

### 2.1. Unutarnje spremanje (na uređaju)

Podaci koje aplikacija trenutno prikazuje čuvaju se u
`EncryptedSharedPreferences` (Android Jetpack Security biblioteka), gdje je
ključ za šifriranje spremljen u **Android Keystoreu** (hardverski podržan
sigurnosni modul na većini uređaja). Ovi podaci su vezani uz taj specifični
uređaj/instalaciju — ne mogu se ručno prenijeti na drugi telefon kopiranjem
datoteke.

### 2.2. Vanjski šifrirani backup (prenosiv)

Za sigurnosnu kopiju koja se može prenijeti na drugi uređaj ili čuvati izvan
telefona, MojSafe koristi **potpuno neovisan** format datoteke:

| Komponenta | Algoritam | Parametri |
|---|---|---|
| Izvod ključa iz lozinke (KDF) | **Argon2id** | memorija: 4096 KiB, iteracije: 3, paralelizam: 1, izlaz: 256-bitni ključ |
| Enkripcija podataka | **AES-256-GCM** | autenticirana enkripcija (netočna lozinka ili oštećenje datoteke odmah javljaju grešku, ne vraćaju "smeće") |
| Sol (salt) | 16 nasumičnih bajtova, jedinstvena za svaki backup | generirana putem `SecureRandom` |
| IV (inicijalizacijski vektor) | 12 nasumičnih bajtova, jedinstven za svaki backup | generiran putem `SecureRandom` |

Argon2id je pobjednik natjecanja *Password Hashing Competition* i trenutno
preporučen standard za izvođenje ključeva iz lozinki upravo zato što je
*memorijski zahtjevan* — čini napade grubom silom (posebno na GPU/ASIC
uređajima) bitno sporijima i skupljima nego kod starijih metoda (npr. čisti
PBKDF2 ili SHA-256).

**Format datoteke** (binarni, redom):
```
"MSFE1"  (5 bajtova, magic broj)
salt     (16 bajtova)
iv       (12 bajtova)
ciphertext + GCM tag  (ostatak datoteke)
```

Lozinka za backup je **potpuno neovisna** o lozinci za otključavanje
aplikacije — možete koristiti istu ili različitu. Ako se lozinka za backup
izgubi, podaci se **ne mogu** oporaviti (ovo je namjerno svojstvo jake
enkripcije, ne greška).

---

## 3. Struktura podataka

Svaka stavka (mapa ili kartica) ima:

| Polje | Opis |
|---|---|
| `uid` | Jedinstveni identifikator (broj) |
| `parent` | `uid` roditeljske mape (`0` = korijen/root) |
| `attr` | Vrsta stavke: `1` ili `3` = mapa, `5` = kartica |
| `time` | Vremenska oznaka zadnje izmjene |
| `strings` | Popis tekstualnih nizova: `strings[0]` je naslov; za kartice, `strings[1..]` su parovi naziv/vrijednost |

Premještanje stavke je promjena samo njenog `parent` polja — ako se premješta
mapa, sav sadržaj unutar nje automatski "putuje" s njom jer djeca i dalje
referenciraju isti `uid` roditelja.

---

## 4. Korištenje aplikacije

### 4.1. Prvo pokretanje
Aplikacija traži postavljanje lozinke (i potvrdu) prije prvog korištenja. Ta
lozinka štiti pristup aplikaciji na ovom uređaju.

### 4.2. Uvoz starih podataka (Handy Safe migracija)
Preko izbornika (⋮) → **"Encrypted restore…"** — unesite lozinku kojom je
backup zaštićen, odaberite `.mojsafe` datoteku, podaci se učitavaju.

### 4.3. Dodavanje / izmjena
- Dva "+" gumba dolje desno: novi folder / nova kartica.
- Svaki redak u popisu ima ⋮ izbornik: **Edit**, **Move**, **Delete** — radi na
  svim razinama, uključivo korijensku.
- Unutar kartice, redoslijed polja mijenja se strelicama gore/dolje pored
  svakog polja.

### 4.4. Premještanje (Move)
Otvara se ravni, uvučeni popis svih mapa u bazi. Odabir odredišta odmah
premješta stavku (uz potvrdu). Mapa se ne može premjestiti u samu sebe ili
svoju podmapu (spriječeno u sučelju).

### 4.5. Backup i restore
- **⋮ → Encrypted backup…** — unesite (i potvrdite) lozinku, zatim odaberite
  gdje spremiti `.mojsafe` datoteku (lokalno ili bilo koji cloud servis
  registriran na uređaju).
- **⋮ → Encrypted restore…** — unesite lozinku, odaberite datoteku.

### 4.6. Tema i lozinka
**⋮ → Theme** (Light / Dark / Follow system) i **⋮ → Change password** (traži
trenutnu pa novu lozinku).

---

## 5. Za programere — struktura projekta

```
app/src/main/java/com/example/safeimport/
├── MainActivity.kt        UI (Jetpack Compose): zaključavanje, navigacija,
│                          uređivanje, backup/restore, tema
├── Models.kt               Podatkovni model (VaultItem) i pomoćne funkcije
├── VaultRepository.kt       Unutarnje šifrirano spremanje (EncryptedSharedPreferences)
└── SecureBackup.kt          Vanjski šifrirani backup format (Argon2id + AES-256-GCM)
```

### 5.1. Build

```bash
# lokalno (Android Studio) — otvorite projekt, Gradle sync, Build APK
# ili automatski putem GitHub Actions (.github/workflows/build-apk.yml):
#   push na granu main pokreće build; gotov .apk je "artifact" tog builda
#   (dostupan i na grani release-apk, commit-an direktno u repozitorij)
```

Potpisivanje (debug) koristi **fiksni** keystore (`debug.keystore`, spremljen u
repozitoriju) kako bi svaka nova verzija mogla nadograditi prethodnu bez
potrebe za deinstalacijom.

### 5.2. Poznata ograničenja

- Podaci uvezeni iz stare Handy Safe baze prošli su kroz heuristički
  (best-effort) postupak izvlačenja teksta — većina polja (naslovi, imena,
  banke, PIN-ovi) izvučena je čisto, ali pokoje čisto brojčano polje (npr.
  neki brojevi kartica) možda se nije prepoznalo kao tekst i treba ga ručno
  dopuniti.
- Aplikacija je trenutno dostupna samo kao debug build (nije objavljena na
  Google Play), pa je potrebno ručno dopustiti instalaciju iz nepoznatih
  izvora.

---

## 6. Povijest izmjena (sažetak)

1. Migracija podataka iz Handy Safe (analiza formata, dešifriranje, izvoz u
   JSON).
2. Osnovna aplikacija: pregled uvezenih podataka, EncryptedSharedPreferences.
3. Zaključavanje lozinkom s mogućnošću promjene.
4. Puno uređivanje: dodavanje/izmjena/brisanje mapa i kartica, na svim
   razinama.
5. Tamna/svijetla tema.
6. Ispravak zamrzavanja/rušenja (Move ekran, ANR watchdog, dijagnostika bez
   pristupa uređaju).
7. Preimenovanje u MojSafe, vlastita ikona i logo.
8. Premještanje (Move) stavki uz zaštitu od kružnih referenci.
9. Šifrirani backup/restore (Argon2id + AES-256-GCM), uklonjen nešifrirani
   izvoz.
10. Automatsko zaključavanje pri odlasku u pozadinu.
11. Ispravak spremanja naslova kartice (naslov se ranije pogrešno spremao kao
    riječ "Title" umjesto stvarne vrijednosti).
12. Mogućnost mijenjanja redoslijeda polja unutar kartice.

---

*Ova dokumentacija opisuje stanje aplikacije u trenutku pisanja. Za najnoviji
kod i povijest izmjena pogledajte git log repozitorija.*
