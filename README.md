# LLMCraft — dowolny LLM w Twoim kliencie Minecrafta (Fabric 1.21.11)

LLMCraft podłącza dowolny model językowy do klienta gry: model czyta czat, świat, ekwipunek, otwarte GUI i log klienta, a może pisać na czacie, wykonywać komendy, korzystać z innych modów i sterować graczem. **Każdą** z tych zdolności włączasz/wyłączasz osobno (`OFF` / `ASK` / `ALLOW`), osobno dla singleplayera, multiplayera i konkretnego świata/serwera.

- **Dowolne źródło AI**: Ollama (natywnie), każde API zgodne z OpenAI (OpenAI, OpenRouter, Groq, LM Studio, llama.cpp, vLLM, DeepSeek, Mistral, Gemini, Ollama `/v1`…), Anthropic Claude. Wiele profili, przełączanie w locie.
- **Modele bez function calling** też działają (tryb `JSON`), a modele z vision dostają zrzuty ekranu.
- **Globalne instrukcje** + **pamięć, historia i kontekst osobno dla każdego świata/serwera** (z automatycznym streszczaniem starej historii).
- **Integracja z innymi modami**: komendy klienckie, komendy „prefiksowe” (Meteor `.`, Baritone `#` — także `baritone-meteor`), dowolne keybindy, symulacja fizycznych klawiszy, GUI innych modów, ich pliki konfiguracyjne, Baritone przez API.

## Wymagania i instalacja

| | |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | ≥ 0.17 |
| Fabric API | wymagane |
| Mod Menu + Cloth Config | opcjonalne — ekran ustawień |
| Baritone (także fork Meteora) | opcjonalne — narzędzie `baritone` |

Skopiuj `build/libs/llmcraft-1.3.0.jar` do folderu `mods` (usuń starszą wersję).

## Szybki start (lokalna Ollama)

1. `ollama pull qwen3.5:4b` (lub inny model z obsługą *tools*; lista: `ollama list`, kolumna capabilities).
2. Wejdź do świata i wpisz `/llm test` — powinno odpowiedzieć „pong”.
3. Rozmawiaj: `/ai co mam w ekwipunku?`, albo w czacie `@ai idź 10 bloków na północ`, albo klawisz **J**.
4. **End** = awaryjny stop wszystkiego (zwalnia klawisze, przerywa zapytanie, anuluje Baritone).

Domyślne klawisze (do zmiany w *Opcje → Sterowanie → LLMCraft*): **J** rozmowa, **End** stop, **=** zatwierdź, **-** odrzuć.

## Pliki

Wszystko leży w `.minecraft/config/llmcraft/`:

```
config.json                 ← cała konfiguracja (edytowalna też z gry)
global_instructions.md      ← instrukcje obowiązujące wszędzie (Twoje preferencje; strategia gry jest wbudowana — `agent.includePlaybook`)
global_memory.json          ← pamięć globalna (np. preferencje gracza)
worlds/<klucz>/             ← osobno dla każdego świata/serwera
    instructions.md         ← instrukcje tylko dla tego świata/serwera
    memory.json             ← fakty: bazy, koordynaty, gracze, cele
    history.json            ← historia rozmów
    summary.md              ← streszczenie starszej historii
    settings.json           ← nadpisania: uprawnienia, profil, wyzwalacze, wł./wył.
```

Klucz świata: `sp_<folder zapisu>` dla singleplayera, `mp_<adres serwera>` dla multiplayera. Pliki `.md` są czytane przy każdym zapytaniu — edytuj je w dowolnym edytorze (`/llm instructions` daje klikalne linki).

## Providerzy

Profile są w `config.json` → `profiles`, aktywny w `activeProfile` (`/llm profile <nazwa>`). Klucz API można podać wprost albo jako `env:NAZWA_ZMIENNEJ`; najprościej z gry: `/llm key claude sk-ant-…` (sam `/llm key claude` pokazuje, skąd klucz jest brany).

> **`env:` a launcher**: zmienna musi być widoczna dla procesu Minecrafta. W Prism Launcher ustawisz ją w *Edytuj instancję → Ustawienia → Zmienne środowiskowe* (potem restart gry). Gdy klucza brak, mod mówi to przed wysłaniem zapytania, zamiast pokazywać błąd 400/401 od dostawcy.

| Profil | `type` | `baseUrl` | Uwagi |
|---|---|---|---|
| Ollama | `OLLAMA` | `http://localhost:11434` | `extraBody`: `{"keep_alive":"30m","think":false}` |
| OpenAI | `OPENAI` | `https://api.openai.com/v1` | dla modeli rozumujących `maxTokensField: "max_completion_tokens"` |
| Claude | `ANTHROPIC` | `https://api.anthropic.com` | `maxTokens` wymagane (domyślnie 4096) |
| OpenRouter | `OPENAI` | `https://openrouter.ai/api/v1` | |
| Gemini | `OPENAI` | `https://generativelanguage.googleapis.com/v1beta/openai` | |
| LM Studio | `OPENAI` | `http://localhost:1234/v1` | |
| llama.cpp / vLLM | `OPENAI` | `http://host:port/v1` | |

> **Okno kontekstu**: same schematy ~49 narzędzi to ok. 5 tys. tokenów. Dla modeli lokalnych domyślnie `contextTokens: 16384` (Ollama dostaje to jako `num_ctx`; więcej kontekstu = więcej VRAM). Przy 8192 historia rozmowy praktycznie się nie zmieści — mod przytnie ją automatycznie i ostrzeże w czacie. Wyłączenie nieużywanych kategorii uprawnień (`OFF`) zmniejsza liczbę wysyłanych narzędzi.

Pola profilu: `model`, `temperature`, `maxTokens`, `contextTokens` (budżet historii; dla Ollamy też `num_ctx`), `toolMode` (`NATIVE` / `JSON` / `NONE`), `vision`, `timeoutSeconds`, `headers`, `extraBody` (dowolne pola doklejane do zapytania).

Każdy świat/serwer może używać innego profilu: `/llm world profile <nazwa>`.

Nowy profil z poziomu gry (indeks = liczba istniejących profili, domyślnie 6):
`/llm config set profiles.6 {"name":"groq","type":"OPENAI","baseUrl":"https://api.groq.com/openai/v1","apiKey":"env:GROQ_API_KEY","model":"llama-3.3-70b-versatile"}`

## Uprawnienia

| Kategoria | Co daje | SP | MP |
|---|---|---|---|
| `chat_read` | czytanie czatu (+ ostatni czat w kontekście) | ALLOW | ALLOW |
| `chat_send` | publiczne wiadomości | ALLOW | ASK |
| `commands` | komendy serwera | ASK | ASK |
| `client_commands` | komendy klienckie innych modów, Meteor `.`, Baritone `#` | ASK | ASK |
| `world_read` | bloki, encje, czas, pogoda, biom, gracze, scoreboard | ALLOW | ALLOW |
| `player_read` | status i ekwipunek | ALLOW | ALLOW |
| `screen` | odczyt i obsługa GUI/kontenerów (też z innych modów) | ALLOW | ALLOW |
| `inventory` | crafting, zakładanie, wyrzucanie przedmiotów (bez GUI) | ALLOW | ASK |
| `movement` | ruch, kamera, chodzenie do punktu | ALLOW | ALLOW |
| `interaction` | atak, użycie, jedzenie, kopanie, stawianie bloków, skrzynie i piece | ALLOW | ASK |
| `keybinds` | naciskanie keybindów i fizycznych klawiszy | ASK | ASK |
| `mods` | lista modów, metadane, pliki konfiguracyjne | ALLOW | ALLOW |
| `baritone` | sterowanie Baritone | ALLOW | ASK |
| `client_log` | `logs/latest.log` | ALLOW | ALLOW |
| `vision` | zrzuty ekranu (tylko profile z `vision: true`) | ALLOW | ALLOW |
| `memory` | zapis do pamięci | ALLOW | ALLOW |

- `/llm perms` — lista z klikalnym przełączaniem.
- `/llm perm <kategoria> <off|ask|allow> [world|singleplayer|multiplayer|all]`.
- **ASK**: w czacie pojawia się prośba z przyciskami *Allow* / *Always this session* / *Deny* (albo klawisze `=` / `-`). Przyciski mają losowe, nieodgadywalne ID — serwer nie podrobi zgody.
- Kategoria `OFF` = model w ogóle nie widzi tych narzędzi.
- `commandDenyPatterns` (np. `op`, `ban`, `stop`) blokują komendy zawsze; `commandAutoAllowPatterns` przepuszczają bez pytania.

## Narzędzia (≈54)

Najważniejsza zasada: **model nie klika po GUI**. Crafting, skrzynie, piece, zakładanie przedmiotów i kopanie to pojedyncze wywołania, które mod wykonuje sam — tymi samymi pakietami, co gra (kliknięcia slotów, przycisk „umieść recepturę” z recipe booka). Zrzuty ekranu i kursor nie są potrzebne do zwykłej gry.

- **Planowanie**: `plan` — cały cel rozpisany do bloków do wykopania (np. `nether_portal`, `iron_pickaxe, 2 bucket`, `diamond_tools`): najpierw brakujące kilofy, potem zbieranie hurtem (każdy surowiec raz, z sumą na cały cel), przetapianie z paliwem, crafting; liczy to, co już masz w ekwipunku
- **Przedmioty (bez GUI)**: `craft` (rekurencyjnie: kłody → deski → patyki → stół → kilof w jednym wywołaniu; sam stawia/używa stołu i zabiera go potem z powrotem; wzór `grid` dla receptur spoza recipe booka), `recipe_info`, `equip` (ręka/druga ręka/zbroja po nazwie), `eat`, `drop_items`
- **Świat**: `mine` (zbierz N bloków albo przedmiotów — `diamond`, `raw_iron`, `flint`, `obsidian` liczą zdobyte przedmioty; z Baritone szuka w całym świecie i sam eksploruje; sprawdza, czy masz narzędzie, które da drop; rudy obejmują wariant deepslate), `use_container` (otwórz → weź/włóż → zamknij), `smelt` (piec/wędzarnia/piec hutniczy; sam stawia piec i zabiera go potem, dobiera tanie paliwo — nigdy stół czy skrzynię — ładuje tylko tyle, ile paliwo przetopi), `build_nether_portal` (szuka płaskiego miejsca obok, stawia ramę 4×5 z rogami z bruku/ziemi i zapala)
- **Czat/komendy**: `read_chat`, `send_chat`, `run_command`, `mod_chat_command`, `list_client_commands`
- **Odczyt**: `player_status`, `inventory`, `world_info`, `get_block`, `find_blocks`, `nearby_entities`, `wait`
- **Ruch**: `look`, `look_at`, `move`, `walk_to`, `stop_actions`
- **Interakcja**: `use_block` (prawy klik / krótkie uderzenie bloku po nazwie: piec, skrzynia, drzwi, dźwignia, łóżko; gdy jest dalej — najpierw `goto <blok>` Baritone), `attack`, `use_item`, `interact_entity`, `mine_block` (sam dobiera najlepsze narzędzie), `place_block` (z parametrem `item`; nigdy nie klika w stół/skrzynię/drzwi)
- **Walka i zagrożenia**: `fight` — najlepsza broń, pościg, cios przy pełnym naładowaniu, odskok od creepera, sam przerywa przy niskim HP (`retreat_health`); cele: `hostiles`, typ moba, `player:<nick>` (tylko na wyraźne polecenie gracza, nie z wyzwalacza), `entity_id`; `mode=flee` — ucieczka od zagrożeń z omijaniem urwisk i lawy
- **Zablokowanie**: `unstuck` — diagnoza (szyb 1×1, zakopany, ślepy zaułek, woda, pajęczyna) i szybkie wyjścia: Baritone do otwartego miejsca, pillar w górę, przekop w bok, wypłynięcie; po 2 próbach bez postępu przerywa i każe przemyśleć sytuację
- **Notatki zadania**: `task_notes` — notatnik modelu na czas zapytania (cel, współrzędne, co zostało), widoczny w każdym kolejnym kroku
- **GUI innych modów**: `read_screen`, `click_widget`, `type_text`, `click_slot`, `close_screen`
- **Klawisze**: `list_keybinds`, `press_keybind`, `press_key`
- **Mody**: `list_mods`, `mod_info`, `list_config_files`, `read_config_file`, `baritone`
- **Log**: `read_client_log` · **Wizja**: `screenshot` (rzadko potrzebny) · **Pamięć**: `remember`, `update_memory`, `forget`, `list_memory`

Każdy wynik akcji kończy się zmianą ekwipunku (`inventory: +3 oak_log, -1 stick`) i linią statusu (`[hp 20/20 food 18 | pos … | holding … | baritone idle]`), więc model nie musi zgadywać, co się stało.

### Crafting — skąd mod zna receptury
- Receptury z **recipe booka** gracza są układane pakietem recipe booka (serwer sam przenosi składniki).
- W **singleplayerze** mod zna też receptury jeszcze nieodblokowane (bierze je z serwera zintegrowanego) i układa je zwykłymi kliknięciami — dlatego „kilof z samych kłód” działa od razu.
- Na **serwerze** nieodblokowane receptury nie są znane klientowi: model dostaje komunikat i może podać wzór `grid` (np. `[["oak_planks","oak_planks"],["oak_planks","oak_planks"]]`).

### Bezpieczeństwo w trakcie pracy
- Gdy gracz się topi (mało powietrza) albo stoi w lawie, mod przerywa wszystkie akcje AI i Baritone, zamyka GUI i wypływa w górę; model dostaje o tym informację.
- Odruchy (tylko gdy AI działa, `control.autoFlee`): ucieczka przed syczącym creeperem i przed potworami przy HP ≤ `control.fleeHealth` (6); własne chodzenie AI zatrzymuje się przed lawą i spadkiem 4+ bloków. Linia statusu pokazuje zagrożenia (`threats: zombie 5m…`) i zablokowanie.
- Watchdog postępu: zadania ruchu (Baritone goto/mine, chodzenie, pościg) kończą się po ~8 s bez żadnego postępu (ruch, kopanie, zbieranie, liczenie ścieżki) z diagnozą, zamiast czekać do timeoutu. To samo nieudane wywołanie dwa razy z rzędu jest blokowane, dopóki gracz nie zmieni miejsca albo ekwipunku.
- Komendy, których serwer Ci nie udostępnia (np. `/give` bez cheatów), są odrzucane przed wysłaniem; w stanie gry model widzi „Operator commands: allowed / NOT allowed”.
- Komunikaty Baritone/Meteor (które omijają zwykłe zdarzenia czatu) są przechwytywane i trafiają do wyników narzędzi, np. „Unable to find any path”.

### Jak AI „używa innych modów”
- `run_command` — komendy klienckie modów (Fabric) wykonują się lokalnie, reszta idzie na serwer.
- `mod_chat_command` — komendy przechwytywane z czatu (Meteor `.`, Baritone `#`); działa **tylko**, gdy dany mod jest zainstalowany (`modChatPrefixes`), więc nic nie wycieknie na czat publiczny.
- `press_keybind` — dowolny keybind z menu Sterowanie (vanilla i mody, np. mapa Xaero).
- `press_key` — symulacja fizycznego klawisza dla modów z własnym systemem skrótów (np. Meteor) i w GUI.
- `read_screen` / `click_widget` / `type_text` — obsługa ekranów dowolnego moda.
- `baritone` — bezpośrednio przez API Baritone (bez czatu), z czekaniem na zakończenie i komunikatami Baritone w wyniku. Obsługuje każdą komendę zainstalowanego Baritone: `help` / `help <komenda>` (opisy prosto z jara), `settings <fraza>` / `get <nazwa>` / `set <nazwa> <wartość>` (konfiguracja w locie), waypointy (`sethome`, `home`, `waypoints save user <nazwa>`, `waypoints goto <nazwa>`), `follow`, `explore`, `farm`, `tunnel`, `pickup`, `surface`… Komendy ciągłe (follow/explore/farm/elytra) domyślnie nie blokują. Uwaga: `goto x y z` znaczy „stań w tym bloku” — mod blokuje cel w pełnym bloku (Baritone by go wykopał).

## Kontekst, cache i inferencja

**Tryb `agent.contextMode = "fresh"` (domyślny).** Każdy krok wysyła: ten sam prompt systemowy + schematy narzędzi + historię (bajt w bajt identyczne → serwer trzyma je w cache KV) i **jedną** odbudowaną wiadomość na końcu: polecenie, notatki modelu (`task_notes`), przypięty wynik `plan`, jednolinijkowy log tego, co już zrobione, pełne wyniki ostatniego kroku i aktualny stan gry. Kontekst nie rośnie z krokami, a przeliczany jest tylko ogon. Stan gry i czat nie siedzą już w prompcie systemowym, więc cache działa też między zapytaniami. Pomiar w gametestach (Ollama, ~10,5k tokenów promptu): pierwszy prefill ~1,9 s, każdy kolejny krok ~0,3 s. Czasy prefillu widać w logu (`[llm] step N: prompt … tok, prefill … ms`) i na czacie po włączeniu `chat.showTokenUsage`. Stary tryb rosnącej rozmowy: `"append"`.

**Ollama (serwer).** Najważniejsze zmienne środowiskowe: `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q4_0` (albo `q8_0` — mniej pamięci niż f16, q4 ~połowa q8), `OLLAMA_NUM_PARALLEL=1` (jeden slot = pewne trafienia w cache prefiksu), `keep_alive` w `extraBody` profilu (np. `"30m"`), żeby model nie był wyładowywany między zapytaniami. `num_ctx` mod wysyła sam z `contextTokens` profilu.

**Speculative decoding.** Ollama (0.40) ma modele draft (`DRAFT` w Modelfile) tylko w runnerze MLX dla modeli safetensors — dla GGUF na CUDA/ROCm nie działa. Dla GGUF użyj `llama-server` z llama.cpp i profilu typu OpenAI:
```
llama-server -m Qwen3.8-27B-Q4_K_M.gguf -md Qwen3.5-0.8B-Q8_0.gguf --draft-max 16 --draft-min 2 \
  -ngl 99 -ngld 99 -fa on -ctk q4_0 -ctv q4_0 -c 131072 --cache-reuse 256 --jinja --port 8080
```
Model draft musi mieć ten sam tokenizer (ta sama rodzina, np. mały Qwen do dużego Qwena). W `config.json` profil: `"type": "OPENAI"`, `"baseUrl": "http://localhost:8080/v1"`, `"model": "qwen"`, `"toolMode": "NATIVE"`. `--jinja` jest potrzebne do natywnych wywołań narzędzi, `--cache-reuse` do ponownego użycia cache prefiksu.

## Wyzwalacze automatyczne (domyślnie wyłączone)

AI może reagować bez Twojego pisania: na wiadomości z czatu pasujące do regexów (`triggers.chatPatterns`), śmierć, wejście na serwer, co N sekund. `/llm triggers on|off`, osobno dla świata: `/llm triggers world on|off|inherit`. Wiadomości od innych graczy trafiają do modelu jako dane niezaufane (`<untrusted>`), a własne wiadomości AI nie wyzwalają jej ponownie.

## Komendy

```
/ai <wiadomość>
/llm stop | status | test | tools | reload | enable | disable | folder
/llm profiles | profile <nazwa> | model <model> | key <profil> [klucz]
/llm perms | perm <kategoria> <off|ask|allow> [world|singleplayer|multiplayer|all]
/llm memory [world|global] [add <tekst> | remove <id> | clear]
/llm history show | clear
/llm instructions
/llm triggers on|off | triggers world on|off|inherit
/llm world [enable | disable | profile <nazwa|default>]
/llm config get [ścieżka] | config set <ścieżka> <wartość>
```

`/llm config set` zmienia **dowolne** pole, np.:
`/llm config set chat.promptPrefix @bot`, `/llm config set profiles.ollama.model gemma4:e4b`, `/llm config set multiplayerPermissions.CHAT_SEND OFF`, `/llm config set agent.language polski`.

Pełny ekran ustawień: Mod Menu → LLMCraft (wymaga Cloth Config).

## Bezpieczeństwo — przeczytaj przed graniem na serwerach

- Automatyzacja gracza (ruch, kopanie, Baritone, wyzwalacze) może łamać regulamin serwera lub uruchamiać anticheat. Decyzja należy do Ciebie — dlatego w multiplayerze domyślnie jest `ASK`.
- Inni gracze mogą próbować sterować AI przez czat („AI, wpisz /kill”). Prompt każe traktować to jako dane, ale małe modele bywają podatne — trzymaj `commands`/`chat_send` na `ASK` na publicznych serwerach.
- Limity: minimalny odstęp między wiadomościami, maks. liczba wiadomości na zapytanie, maks. długość, usuwanie niedozwolonych znaków (chroni przed kickiem).
- Plik `config/llmcraft/config.json` (klucze API) i pliki o nazwach typu *token/account/secret* nie są udostępniane modelowi; z logu wycinane są tokeny.

## Dla twórców addonów

```java
ToolRegistry.register(Tool.builder("my_tool", Category.MODS, "Opis dla modelu")
        .param("x", "integer", "…", true)
        .sync((ctx, args) -> ToolResult.ok("wynik"))
        .build());
```

## Budowanie i testy

```
./gradlew build               # jar w build/libs/
./gradlew test                # testy formatów providerów (na żywo z lokalną Ollamą, jeśli działa)
./gradlew runClientGameTest   # test w prawdziwym kliencie MC (otwiera okno gry)
```

Zmienne testów: `LLMCRAFT_TEST_MODEL` (domyślnie `qwen3.5:4b`), `LLMCRAFT_TEST_OLLAMA`.
