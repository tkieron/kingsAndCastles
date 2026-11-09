# Wdrożenie

Gra ma dwie części:

- **klient** (to, co dzieci otwierają w przeglądarce): GitHub Pages, adres `https://tkieron.github.io/kingsAndCastles/`;
- **serwer** (pokoje, tury, zapis gier): mikr.us, adres `https://kc.pointsapp.pl`.

## 1. Klient na GitHub Pages (raz)

1. W repozytorium: **Settings → Pages → Build and deployment → Source: GitHub Actions**.
2. Gotowe. Każda zmiana w `client/` na gałęzi `main` publikuje grę sama (workflow „Publish the game”).
   Pierwszy raz można go uruchomić ręcznie: **Actions → Publish the game → Run workflow**.

Klient łączy się z `https://kc.pointsapp.pl`. Inny adres ustawisz w **Settings → Secrets and variables →
Actions → Variables** jako `KC_SERVER_URL` (potem uruchom workflow ponownie).

## 2. Domena kc.pointsapp.pl

Mikrus ma publiczny adres **IPv6**, a IPv4 jest współdzielony (bez portów 80 i 443). Sieci szkolne
i domowe często mają tylko IPv4, więc najpewniej jest postawić domenę za **Cloudflare**:

1. W DNS domeny `pointsapp.pl` dodaj rekord **AAAA**: nazwa `kc`, wartość = adres IPv6 Mikrusa
   (w panelu Mikrusa albo `ip -6 addr` na serwerze).
2. Jeśli DNS jest w Cloudflare: włącz pomarańczową chmurkę (Proxied) dla `kc`, a w **SSL/TLS** ustaw tryb **Full**.
   WebSockety działają w Cloudflare bez dodatkowych ustawień.

Jeśli DNS domeny nie jest w Cloudflare, sam rekord AAAA też zadziała, ale tylko dla urządzeń z IPv6.

## 3. Serwer na Mikrusie

Zaloguj się na serwer i uruchom jako root:

```bash
git clone https://github.com/tkieron/kingsAndCastles.git /opt/kingscastles-src
cd /opt/kingscastles-src
sudo deploy/install.sh
```

Skrypt:

- instaluje Javę 21, Mavena i Caddy;
- buduje serwer;
- zakłada użytkownika `kingscastles` i usługę `kingscastles` (start po restarcie maszyny);
- ustawia Caddy dla `kc.pointsapp.pl`: HTTPS sam się odnawia, WebSockety przechodzą.

Zapisane pokoje leżą w `/var/lib/kingscastles`, po jednym pliku JSON na pokój. Serwer zajmuje
około 150–250 MB RAM (limit ustawiony na 256 MB).

Jeśli repozytorium jest prywatne, `git clone` zapyta o login; użyj tokenu z GitHuba
(Settings → Developer settings → Personal access tokens) z prawem do odczytu repozytorium.

## Aktualizacja serwera

```bash
cd /opt/kingscastles-src && sudo deploy/install.sh
```

Skrypt pobiera `main`, buduje i restartuje usługę. Trwające gry wracają dokładnie w tym samym miejscu.

## Sprawdzenie

- `https://kc.pointsapp.pl/api/rules` pokazuje zasady gry (JSON);
- `systemctl status kingscastles` pokazuje, czy usługa działa;
- `journalctl -u kingscastles -n 100` to ostatnie wpisy z serwera.
