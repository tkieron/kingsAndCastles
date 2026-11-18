# Wdrożenie

Gra działa pod jednym adresem: **https://kc.pointsapp.pl**. Serwer w Javie na Mikrusie podaje stronę gry,
API i WebSocket, a przed nim stoją Caddy (HTTPS) i Cloudflare (adres IPv4 i IPv6 dla wszystkich).

```
dziecko ──► Cloudflare ──► Caddy na Mikrusie (HTTPS) ──► serwer gry 127.0.0.1:8080
                                                           ├─ strona gry (client/dist)
                                                           ├─ /api/…
                                                           └─ /ws
```

Wdraża GitHub Actions (`.github/workflows/deploy.yml`) **po każdym scaleniu do `main`**. Można też uruchomić
ręcznie: **Actions → Deploy to kc.pointsapp.pl → Run workflow**. Workflow:

1. buduje grę i puszcza wszystkie testy (jak coś nie przechodzi, nic nie jest wdrażane);
2. odczytuje z Mikrusa jego adres IPv6 i ustawia w Cloudflare rekord **AAAA `kc`** (przez Cloudflare, „pomarańczowa chmurka”);
3. kopiuje gotową grę na Mikrusa i uruchamia tam `deploy/install.sh` (Java 21, Caddy, usługa `kingscastles`);
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
- na Mikrusie: `systemctl status kingscastles` i `journalctl -u kingscastles -n 100`.

## Gdy coś nie działa

- **Workflow pisze „Missing repository secrets”**: brakuje sekretu z tabeli wyżej.
- **„Host key verification failed”**: `MIKRUS_KNOWN_HOSTS` nie pasuje, wygeneruj go ponownie.
- **„The Cloudflare token cannot see the zone”**: token nie ma dostępu do `pointsapp.pl`.
- **„cannot read Configuration Rules”**: dodaj tokenowi uprawnienie Zone → Config Rules → Edit.
- **„Permission denied (publickey)”**: publicznej części klucza (`kc-deploy.pub`) nie ma w `/root/.ssh/authorized_keys` na Mikrusie.
- **Usługa nie startuje z błędem `226/NAMESPACE`**: kontener Mikrusa nie pozwala na część zabezpieczeń systemd.
  Zakomentuj w `deploy/kingscastles.service` linie `ProtectSystem`, `ProtectHome` i `PrivateTmp` i uruchom workflow ponownie.
- **Strona nie odpowiada zaraz po pierwszym wdrożeniu**: nowy rekord DNS i certyfikat HTTPS potrzebują kilku minut.

## Ustawienia serwera

W `deploy/kingscastles.service` (zmienne środowiskowe):

| Zmienna | Wartość | Co robi |
|---|---|---|
| `KC_CLIENT_DIR` | `/opt/kingscastles/client` | skąd serwer podaje stronę gry |
| `KC_DATA_DIR` | `/var/lib/kingscastles` | zapisane pokoje, jeden plik JSON na pokój |
| `KC_ALLOWED_ORIGINS` | `https://kc.pointsapp.pl` | skąd wolno łączyć się z API i WebSocketem |
| `KC_MAX_ROOMS` | `500` | najwięcej pokoi naraz (serwer jest otwarty dla każdego) |
| `KC_ROOM_IDLE_DAYS` | `14` | pokój nieruszany tyle dni jest usuwany |

Serwer zajmuje około 150–250 MB RAM (limit 256 MB) i słucha tylko na 127.0.0.1, więc z internetu
dostępny jest wyłącznie przez Caddy.
