# Wdrożenie

Gra działa pod jednym adresem: **https://kc.pointsapp.pl**. Na Mikrusie stoi już pointsapp w Dockerze, a jego Caddy
trzyma porty 80 i 443. Gra dołącza do tego układu, nie ruszając samego pointsapp:

```
dziecko ──► Cloudflare (SSL strict) ──► Caddy pointsapp (Docker, certyfikat Origin *.pointsapp.pl)
                                          ├─ pointsapp.pl  → web / api (bez zmian)
                                          └─ kc.pointsapp.pl → kontener kingscastles:8080
                                                                ├─ strona gry, /api/…, /ws
                                                                └─ zapisane pokoje: /var/lib/kingscastles
```

- Kontener **kingscastles** (Java 21) jest w sieci Dockera `pointsapp_default` i nie wystawia żadnego portu na zewnątrz.
  Działa jako zwykły użytkownik, z systemem plików tylko do odczytu, bez uprawnień specjalnych i z limitem 400 MB RAM.
- Do `/home/appuser/pointsapp/Caddyfile` wdrożenie dopisuje **jeden blok** `kc.pointsapp.pl` między liniami
  `# BEGIN kings-and-castles` i `# END kings-and-castles` (wzór: `deploy/Caddyfile`). Przed przeładowaniem sprawdza go
  `caddy validate`. Jeśli coś jest nie tak, stary plik wraca, a Caddy niczego nie przeładowuje. Kopia poprzedniej wersji
  leży obok jako `Caddyfile.before-kingscastles`.
- Jeśli kiedyś nadpiszesz Caddyfile pointsapp z innego miejsca, blok gry zniknie. Wtedy wystarczy uruchomić wdrożenie ponownie.

Wdraża GitHub Actions (`.github/workflows/deploy.yml`) **po każdym scaleniu do `main`**. Można też uruchomić
ręcznie: **Actions → Deploy to kc.pointsapp.pl → Run workflow**. Workflow:

1. buduje grę i puszcza wszystkie testy (jak coś nie przechodzi, nic nie jest wdrażane);
2. odczytuje z Mikrusa jego adres IPv6 i ustawia w Cloudflare rekord **AAAA `kc`** (przez Cloudflare, „pomarańczowa chmurka”) oraz SSL „Full (strict)” dla `kc`;
3. kopiuje gotową grę na Mikrusa i uruchamia tam `deploy/install.sh` (kontener gry i blok w Caddy pointsapp);
4. sprawdza, czy https://kc.pointsapp.pl odpowiada.

Trwające gry po aktualizacji wracają dokładnie w tym samym miejscu.

## Raz na początku: sekrety w GitHubie

**Settings → Secrets and variables → Actions → New repository secret.** Sekretów nie wklejaj nigdzie indziej.

| Sekret | Co wpisać |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare → My Profile → API Tokens → Create Token → szablon **Edit zone DNS**, Zone Resources: tylko `pointsapp.pl` |
| `MIKRUS_SSH_KEY` | prywatna część osobnego klucza do wdrażania (niżej) |
| `MIKRUS_HOST` | adres SSH Mikrusa, np. `srv12.mikr.us` |
| `MIKRUS_SSH_PORT` | port SSH Mikrusa, np. `10123` |
| `MIKRUS_KNOWN_HOSTS` | wynik `ssh-keyscan -p <port> <host>`: GitHub połączy się tylko z tym serwerem |
| `MIKRUS_USER` | (nie trzeba) użytkownik SSH, domyślnie `root` |

Klucz do wdrażania (na swoim komputerze):

```bash
ssh-keygen -t ed25519 -f kc-deploy -N "" -C kc-deploy
cat kc-deploy.pub      # dopisz tę linię na Mikrusie do /root/.ssh/authorized_keys
cat kc-deploy          # to wklej jako sekret MIKRUS_SSH_KEY, potem usuń oba pliki z komputera
```

Token Cloudflare potrzebuje uprawnień: **Zone → DNS → Edit**, **Zone → Zone → Read** i **Zone → Config Rules → Edit**,
tylko dla strefy `pointsapp.pl`. Dzięki ostatniemu workflow sam ustawia regułę „SSL Full (strict)” wyłącznie dla
`kc.pointsapp.pl` (`deploy/cloudflare-ssl.sh`), więc inne subdomeny zachowują swój tryb SSL. Bez tej reguły, przy trybie
„Flexible”, strona kręciłaby się w kółko między HTTP i HTTPS.

Czy wszystko jest gotowe, pokaże workflow **Actions → Check the deploy setup → Run workflow**: niczego nie zmienia,
tylko sprawdza sekrety, logowanie SSH, adres IPv6 Mikrusa i token Cloudflare.

## Sprawdzenie

- https://kc.pointsapp.pl to gra, a https://kc.pointsapp.pl/api/rules zasady (JSON);
- na Mikrusie: `docker ps --filter name=kingscastles` i `docker logs --tail 100 kingscastles`.

## Gdy coś nie działa

- **Workflow pisze „Missing repository secrets”**: brakuje sekretu z tabeli wyżej.
- **„Host key verification failed”**: `MIKRUS_KNOWN_HOSTS` nie pasuje, wygeneruj go ponownie.
- **„The Cloudflare token cannot see the zone”**: token nie ma dostępu do `pointsapp.pl`.
- **„cannot read Configuration Rules”**: dodaj tokenowi uprawnienie Zone → Config Rules → Edit.
- **„Permission denied (publickey)”**: publicznej części klucza (`kc-deploy.pub`) nie ma w `/root/.ssh/authorized_keys` na Mikrusie.
- **„Caddy did not accept the new config”**: blok gry nie przeszedł walidacji, Caddyfile pointsapp został przywrócony bez zmian.
- **Strona nie odpowiada zaraz po pierwszym wdrożeniu**: nowy rekord DNS i certyfikat HTTPS potrzebują kilku minut.

## Ustawienia gry

Ustawia je `deploy/install.sh` przy uruchomieniu kontenera:

| Zmienna | Wartość | Co robi |
|---|---|---|
| `KC_CLIENT_DIR` | `/app/client` | skąd serwer podaje stronę gry (na Mikrusie `/opt/kingscastles/client`) |
| `KC_DATA_DIR` | `/data` | zapisane pokoje, jeden plik JSON na pokój (na Mikrusie `/var/lib/kingscastles`) |
| `KC_ALLOWED_ORIGINS` | `https://kc.pointsapp.pl` | skąd wolno łączyć się z API i WebSocketem |
| `KC_MAX_ROOMS` | `500` | najwięcej pokoi naraz (serwer jest otwarty dla każdego) |
| `KC_ROOM_IDLE_DAYS` | `14` | pokój nieruszany tyle dni jest usuwany |
| `KC_PAUSED_ROOM_DAYS` | `30` | zamrożona gra czeka tyle dni na powrót graczy |
| `KC_AUTO_PAUSE_SECONDS` | `90` | gra z limitem czasu tury sama się zamraża, gdy nikt nie jest połączony przez tyle sekund |

Serwer zajmuje około 150–250 MB RAM (sterta 256 MB, kontener 400 MB).
