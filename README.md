# Notatnik – notatki, listy, dyktowanie, nagrania i skany (kolor / e-ink)

## Funkcje
- notatki i listy do odhaczania, kolory jak w Keep, etykiety, przypięte na górze, archiwum
- wyszukiwanie we wszystkim: notatkach, listach, tekście ze skanów
- dyktowanie na żywo (rozpoznawanie mowy w telefonie), nagrania w tle z odtwarzaniem 0,75–2×
- skaner dokumentów Google + odczyt tekstu + nazwa z treści, PDF w Dokumenty/Skany, import starych skanów
- przypomnienia: jednorazowe, za X czasu, codziennie, w wybrane dni, co N godzin/dni/tygodni, co miesiąc, co rok; Gotowe i Odłóż 10 min
- widżety: „Notatki” (zakładki, odhaczanie, dodawanie, przezroczystość, tło, tekst) i „Szybka notatka”
- import z Google Keep (Google Takeout), kopie: GitHub (prywatne repo), Dysk Google, ZIP
- motywy kolorów (jak Karty), tryb e-ink, aktualizacje w aplikacji

- zdjęcia w notatkach (aparat/galeria, tekst ze zdjęć do wyszukiwania), formatowanie (pogrubienie, nagłówki, punkty)
- wspólne notatki z żoną (repozytorium notatki-wspolne, synchronizacja), kosz 30 dni, szablony i duplikowanie
- lista zakupów: podpowiedzi i sortowanie działami; widok „Dziś”; wizytówka → kontakt; blokada odciskiem
- osobna zakładka Przepisy: zdjęcia, porcje z przeliczaniem, tryb gotowania, import z linku, składniki do listy zakupów

## Pierwsze budowanie
1. github.com/new → nazwa `notatnik`, Private, Create repository.
2. Termux (token bierze się z Audioteki):
```
cd ~ && unzip -o "$(ls -t ~/storage/downloads/Notatnik*.zip | head -1)" && cd Notatnik && git init && git add . && git -c user.name=ja -c user.email=ja@x.pl commit -m start && git branch -M main && git remote add origin "$(git -C ~/Audioteka remote get-url origin | sed 's/audioteka/notatnik/')" && git push -u origin main
```
3. github.com/Bartoszelek/notatnik/releases → notatnik.apk → zainstaluj.

## Kolejne wersje
```
cd ~ && unzip -o "$(ls -t ~/storage/downloads/Notatnik*.zip | head -1)" && cd Notatnik && git add -A && git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push
```
