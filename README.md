# LLMCraft — Any LLM in Your Minecraft Client (Fabric 1.21.11)

LLMCraft connects any language model to your game client: the model can read the chat, the game world, your inventory, open GUIs, and the client log. It can also send chat messages, execute commands, interact with other mods, and control your character. You can enable or disable **each** of these capabilities individually (`OFF` / `ASK` / `ALLOW`), with separate settings for singleplayer, multiplayer, and specific worlds or servers.

- **Any AI Source**: Ollama (natively), any OpenAI-compatible API (OpenAI, OpenRouter, Groq, LM Studio, llama.cpp, vLLM, DeepSeek, Mistral, Gemini, Ollama `/v1`, etc.), and Anthropic Claude. Supports multiple profiles with on-the-fly switching.
- **Models without function calling** are also supported (via `JSON` mode), and models with vision capabilities can receive screenshots.
- **Global instructions** + **memory, history, and context managed separately for each world/server** (including automatic summarization of older history).
- **Integration with other mods**: client-side commands, "prefix" commands (e.g., Meteor’s `.` or Baritone’s `#` — including `baritone-meteor`), custom keybinds, simulated physical key presses, interaction with other mods' GUIs and configuration files, and Baritone integration via its API.

## Requirements and Installation

| | |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | ≥ 0.17 |
| Fabric API | Required |
| Mod Menu + Cloth Config | Optional (for the settings screen) |
| Baritone (including the Meteor fork) | optional — `baritone` tool |
| Meteor Client, JsMacros CE | optional — via the **LLMCraft Bridge** addon (see below) |

Copy `build/libs/llmcraft-1.4.0.jar` to your `mods` folder (remove any older version). Integration with Meteor and JsMacros is provided by a separate JAR file: `bridge/build/libs/llmcraft-bridge-1.0.0.jar`.

## Quick Start (Local Ollama)

1. Run `ollama pull qwen3.5:4b` (or another model that supports *tools*; check the "capabilities" column via `ollama list`).
2. Enter a world and type `/llm test` — it should respond with "pong".
3. Start chatting: use `/ai what’s in my inventory?`, type `@ai move 10 blocks north` in the chat, or press the **J** key.
4. **End** = emergency stop (releases keys, interrupts the current request, and cancels Baritone).

Default keybindings (can be changed under *Options → Controls → LLMCraft*): **J** for chat, **End** to stop, **=** to confirm, **-** to reject. ## Files

Everything is located in `.minecraft/config/llmcraft/`:

```
config.json                 ← full configuration (also editable in-game)
global_instructions.md      ← instructions that apply everywhere (your preferences; game strategy is built-in — `agent.includePlaybook`)
global_memory.json          ← global memory (e.g., player preferences)
worlds/<key>/               ← separate for each world/server
instructions.md         ← instructions specific to this world/server
memory.json             ← facts: bases, coordinates, players, objectives
history.json            ← conversation history
summary.md              ← summary of older history
settings.json           ← overrides: permissions, profile, triggers, enabled/disabled status
```

World key: `sp_<save_folder_name>` for singleplayer, `mp_<server_address>` for multiplayer. `.md` files are read with every request — you can edit them in any text editor (`/llm instructions` provides clickable links).

## Providers

Profiles are defined in `config.json` → `profiles`, with the active one set in `activeProfile` (`/llm profile <name>`). You can provide an API key directly or via `env:VARIABLE_NAME`; the easiest way is in-game: `/llm key claude sk-ant-…` (running just `/llm key claude` will show you where the key is currently being sourced from).

> **`env:` and the launcher**: the environment variable must be visible to the Minecraft process. In Prism Launcher, you can set this under *Edit Instance → Settings → Environment Variables* (then restart the game). If the API key is missing, the mod will notify you before sending the request, rather than letting a 400/401 error from the provider occur.

| Profile | `type` | `baseUrl` | Notes |
|---|---|---|---|
| Ollama | `OLLAMA` | `http://localhost:11434` | `extraBody`: `{"keep_alive":"30m","think":false}` |
| OpenAI | `OPENAI` | `https://api.openai.com/v1` | For reasoning models, use `maxTokensField: "max_completion_tokens"` |
| Claude | `ANTHROPIC` | `https://api.anthropic.com` | `maxTokens` is required (defaults to 4096) |
| OpenRouter | `OPENAI` | `https://openrouter.ai/api/v1` | |
| Gemini | `OPENAI` | `https://generativelanguage.googleapis.com/v1beta/openai` | |
| LM Studio | `OPENAI` | `http://localhost:1234/v1` | |
| llama.cpp / vLLM | `OPENAI` | `http://host:port/v1` | |

> **Context window**: The schemas for the ~49 available tools alone account for approximately 5,000 tokens. For local models, the default is `contextTokens: 16384` (passed to Ollama as `num_ctx`; more context requires more VRAM). At 8192 tokens, there is practically no room for conversation history—the mod will automatically truncate it and issue a warning in the chat. Disabling unused permission categories (`OFF`) reduces the number of tools sent to the model.

Profile fields: `model`, `temperature`, `maxTokens`, `contextTokens` (history budget; also serves as `num_ctx` for Ollama), `toolMode` (`NATIVE` / `JSON` / `NONE`), `vision`, `timeoutSeconds`, `headers`, and `extraBody` (arbitrary fields appended to the request).

Each world/server can use a different profile: `/llm world profile <name>`.

Creating a new profile in-game (index = number of existing profiles; defaults to 6):
`/llm config set profiles.6 {"name":"groq","type":"OPENAI","baseUrl":"https://api.groq.com/openai/v1","apiKey":"env:GROQ_API_KEY","model":"llama-3.3-70b-versatile"}`

## Permissions

| Category | Function | SP | MP |
|---|---|---|---|
| `chat_read` | Reading chat (includes recent chat history in context) | ALLOW | ALLOW |
| `chat_send` | Sending public messages | ALLOW | ASK |
| `commands` | Server commands | ASK | ASK |
| `client_commands` | Client-side commands from other mods (e.g., Meteor's `.` or Baritone's `#`) | ASK | ASK |
| `world_read` | blocks, entities, time, weather, biome, players, scoreboard | ALLOW | ALLOW |
| `player_read` | status and inventory | ALLOW | ALLOW |
| `screen` | reading and interacting with GUIs/containers (including those from other mods) | ALLOW | ALLOW |
| `inventory` | crafting, equipping, dropping items (without GUI) | ALLOW | ASK |
| `movement` | movement, camera control, walking to a specific point | ALLOW | ALLOW |
| `interaction` | attacking, using items, eating, mining, placing blocks, accessing chests and furnaces | ALLOW | ASK |
| `keybinds` | triggering keybinds and physical keys | ASK | ASK |
| `mods` | list of mods, metadata, configuration files | ALLOW | ALLOW |
| `baritone` | controlling Baritone | ALLOW | ASK |
| `client_log` | `logs/latest.log` | ALLOW | ALLOW |
| `vision` | screenshots (only for profiles with `vision: true`) | ALLOW | ALLOW |
| `memory` | writing to memory | ALLOW | ALLOW |
| `scripts` | executing macros and scripts (JsMacros, via addon) | ASK | ASK |

- `/llm perms` — a list with clickable toggles.
- `/llm perm <category> <off|ask|allow> [world|singleplayer|multiplayer|all]`.
- **ASK**: a request appears in the chat with buttons for *Allow* / *Always this session* / *Deny* (or corresponding keys `=` / `-`). The buttons use random, unpredictable IDs, ensuring the server cannot forge an authorization. - Category `OFF` = the model cannot see these tools at all.
- `commandDenyPatterns` (e.g., `op`, `ban`, `stop`) always block commands; `commandAutoAllowPatterns` allow them without prompting.

## Tools (≈54)

The most important rule: **the model does not interact with the GUI**. Crafting, accessing chests, using furnaces, equipping items, and mining are handled as individual function calls executed by the mod itself—using the same network packets as the game (e.g., clicking on inventory slots or the "place recipe" button in the recipe book). Screenshots and cursor tracking are not required for standard gameplay.

- **Planning**: `plan` — the entire objective broken down into specific blocks to be mined or items to be acquired (e.g., `nether_portal`, `iron_pickaxe`, `2 bucket`, `diamond_tools`). This includes prioritizing the acquisition of missing tools, bulk resource gathering (collecting each raw material once, with the total quantity calculated for the entire objective), smelting (including fuel management), and crafting. it takes into account what you already have in your inventory
- **Items (no GUI)**: `craft` (recursive: logs → planks → sticks → crafting table → pickaxe in a single call; automatically places/uses the crafting table and retrieves it afterward; uses a `grid` pattern for recipes not found in the recipe book), `recipe_info`, `equip` (main hand/off-hand/armor by name), `eat`, `drop_items`
- **World**: `mine` (collect N blocks or items — for `diamond`, `raw_iron`, `flint`, and `obsidian`, it counts the items obtained; uses Baritone to search the entire world and explore; verifies that you have a tool capable of yielding the drop; includes deepslate variants for ores), `use_container` (open → take/deposit → close), `smelt` (furnace/smoker/blast furnace; automatically places a furnace and retrieves it afterward; selects inexpensive fuel — never crafting tables or chests — and loads only enough fuel for the items being smelted), `build_nether_portal` (finds a flat area nearby, constructs a 4×5 frame with corners made of cobblestone/dirt, and ignites it)
- **Chat/Commands**: `read_chat`, `send_chat`, `run_command`, `mod_chat_command`, `list_client_commands`
- **Reading/Retrieving Info**: `player_status`, `inventory`, `world_info`, `get_block`, `find_blocks`, `nearby_entities`, `wait`
- **Movement**: `look`, `look_at`, `move`, `walk_to`, `stop_actions`
- **Interaction**: `use_block` (right-click / short-click) - Targeting blocks by name (e.g., furnace, chest, door, lever, bed); if a block is further away, Baritone first navigates to it (`goto <block>`). Available actions include `attack`, `use_item`, `interact_entity`, `mine_block` (automatically selects the best tool), and `place_block` (requires an `item` parameter; it will never interact with—i.e., open—crafting tables, chests, or doors).
- **Combat and threats**: The `fight` command automatically selects the best weapon, pursues the target, executes fully-charged strikes, retreats from Creepers, and halts combat when health is low (based on the `retreat_health` threshold).

- to use a different profile: `/llm world profile <name>`.

New profile from within the game (index = number of existing profiles; defaults to 6):
`/llm config set profiles.6 {"name":"groq","type":"OPENAI","baseUrl":"https://api.groq.com/openai/v1","apiKey":"env:GROQ_API_KEY","model":"llama-3.3-70b-versatile"}`

## Permissions

| Category | Function | SP | MP |
|---|---|---|---|
| `chat_read` | Reading chat (+ recent chat context) | ALLOW | ALLOW |
| `chat_send` | Public messages | ALLOW | ASK |
| `commands` | Server commands | ASK | ASK |
| `client_commands` | Client commands from other mods (e.g., Meteor `.`, Baritone `#`) | ASK | ASK |
| `world_read` | Blocks, entities, time, weather, biome, players, scoreboard | ALLOW | ALLOW |
| `player_read` | Status and inventory | ALLOW | ALLOW |
| `screen` | Reading and interacting with GUIs/containers (including those from other mods) | ALLOW | ALLOW |
| `inventory` | Crafting, equipping, dropping items (without GUI) | ALLOW | ASK |
| `movement` | Movement, camera control, pathfinding to a point | ALLOW | ALLOW |
| `interaction` | Attacking, using items, eating, mining, placing blocks, accessing chests/furnaces | ALLOW | ASK |
| `keybinds` | Triggering keybinds and physical key presses | ASK | ASK |
| `mods` | List of mods, metadata, configuration files | ALLOW | ALLOW |
| `baritone` | Controlling Baritone | ALLOW | ASK |
| `client_log` | `logs/latest.log` | ALLOW | ALLOW |
| `vision` | screenshots (only for profiles with `vision: true`) | ALLOW | ALLOW |
| `memory` | writing to memory | ALLOW | ALLOW |
| `scripts` | executing macros and scripts (JsMacros, via addon) | ASK | ASK |

- `/llm perms` — a list with clickable toggles.
- `/llm perm <category> <off|ask|allow> [world|singleplayer|multiplayer|all]`.
- **ASK**: a prompt appears in the chat with buttons: *Allow* / *Always this session* / *Deny* (or keys `=` / `-`). The buttons have random, unpredictable IDs — the server cannot forge an authorization.
- `OFF` category = the model cannot see these tools at all.
- `commandDenyPatterns` (e.g., `op`, `ban`, `stop`) always block commands; `commandAutoAllowPatterns` allow them without asking.

## Tools (≈54)

The most important rule: **the model does not click through the GUI**. Crafting, accessing chests or furnaces, equipping items, and mining are handled as individual function calls executed by the mod itself — using the same network packets as the game (slot clicks, the "place recipe" button from the recipe book, etc.). Screenshots and cursor tracking are not required for standard gameplay. - **Planning**: `plan` — the entire objective broken down into blocks to be mined/acquired (e.g., `nether_portal`, `iron_pickaxe, 2 bucket`, `diamond_tools`): first, obtaining any missing pickaxes, followed by bulk resource gathering (collecting each material once, with the total quantity required for the entire objective), smelting (including fuel), and crafting; it takes into account what you already have in your inventory
- **Items (no GUI)**: `craft` (recursive: logs → planks → sticks → crafting table → pickaxe in a single call; automatically places/uses the crafting table and retrieves it afterward; uses a `grid` pattern for recipes not found in the recipe book), `recipe_info`, `equip` (main hand/off-hand/armor by name), `eat`, `drop_items`
- **World**: `mine` (collect N blocks or items — for `diamond`, `raw_iron`, `flint`, and `obsidian`, it counts the items obtained; uses Baritone to search the entire world and explore; verifies that you have a tool capable of yielding the drop; includes deepslate variants for ores), `use_container` (open → take/deposit → close), `smelt` (furnace/smoker/blast furnace; automatically places a furnace and retrieves it afterward; selects inexpensive fuel — never crafting tables or chests — and loads only enough fuel for the items being smelted), `build_nether_portal` (finds a flat area nearby, constructs a 4×5 frame with corners made of cobblestone/dirt, and ignites it)
- **Chat/Commands**: `read_chat`, `send_chat`, `run_command`, `mod_chat_command`, `list_client_commands`
- **Reading/Retrieving Info**: `player_status`, `inventory`, `world_info`, `get_block`, `find_blocks`, `nearby_entities`, `wait`
- **Movement**: `look`, `look_at`, `move`, `walk_to`, `stop_actions`
- **Interaction**: `use_block` (right-click / short-click) - Interacting with blocks by name (e.g., furnace, chest, door, lever, bed); if the target is further away, it first uses `goto <block>` (via Baritone). Other actions include `attack`, `use_item`, `interact_entity`, `mine_block` (automatically selecting the best tool), and `place_block` (requiring an `item` parameter; it will never click on a crafting table, chest, or door).
- **Combat and threats**: `fight` — uses the best available weapon, pursues targets, strikes only when the attack cooldown is fully charged, retreats from creepers, and automatically disengages when health drops below a certain threshold (`retreat_health`). Targets can be defined as `hostiles`, a specific mob type, a player (by username — only upon explicit player command, not via a trigger), or an `entity_id`. The `mode=flee` setting allows the agent to escape threats while avoiding hazards like cliffs and lava.
- **Getting unstuck**: `unstuck` — diagnoses the situation (e.g., trapped in a 1×1 shaft, buried, in a dead end, submerged in water, or caught in a cobweb) and executes an escape strategy: using Baritone to reach an open area, building a pillar upwards, digging a path to the side, or swimming to the surface. If no progress is made after two attempts, the process stops, prompting a re-evaluation of the situation.
- **Task notes**: `task_notes` — a scratchpad for the model to keep track of the current objective, coordinates, and remaining tasks; this information persists across subsequent steps.
- **Interacting with other mods' GUIs**: `read_screen`, `click_widget`, `type_text`, `click_slot`, and `close_screen`.

- **Keybinds**: `list_keybinds`, `press_keybind`, `press_key`
- **Mods**: `list_mods`, `mod_info`, `list_config_files`, `read_config_file`, `baritone`
- **Log**: `read_client_log` · **Vision**: `screenshot` (rarely needed) · **Memory**: `remember`, `update_memory`, `forget`, `list_memory`

Every action result concludes with an inventory update (e.g., `inventory: +3 oak_log, -1 stick`) and a status line (e.g., `[hp 20/20 food 18 | pos … | holding … | baritone idle]`), ensuring the model does not have to guess what occurred.

### Crafting — how the mod knows recipes
- Recipes from the player's **recipe book** are arranged via a recipe book packet (the server automatically handles the placement of ingredients).
- In **singleplayer** mode, the mod also has access to recipes that have not yet been unlocked (retrieving them from the integrated server) and arranges them through standard clicks—which is why, for example, crafting a pickaxe using only raw logs works immediately.
- On a **server**, recipes that have not yet been unlocked are unknown to the client; in such cases, the model receives a notification and can provide a `grid` pattern (e.g., `[["oak_planks","oak_planks"],["oak_planks","oak_planks"]]`).

### Safety during operation
- If the player begins to drown (running low on air) or stands in lava, the mod interrupts all AI and Baritone actions, closes any open GUIs, and moves the player toward the surface; the model is notified of this event. - Reflexes (active only when AI is enabled, `control.autoFlee`): the AI ​​will flee from hissing creepers or from monsters when HP drops to or below `control.fleeHealth` (6); the AI’s autonomous movement also stops before lava or drops of 4+ blocks. The status line displays detected threats (e.g., `threats: zombie 5m…`) and any movement blocks.
- Progress watchdog: movement tasks (Baritone `goto`/`mine`, walking, chasing) are terminated after ~8 seconds of zero progress (no movement, mining, collecting, or path calculation), accompanied by a diagnostic message, rather than waiting for a full timeout. If the same action fails twice in a row, it is blocked until the player changes their location or inventory state.
- Commands not permitted by the server (e.g., `/give` when cheats are disabled) are intercepted and blocked before being sent; the AI ​​model’s state indicates whether "Operator commands" are allowed or disallowed.
- Baritone/Meteor messages (which bypass standard chat events) are intercepted and routed to the tool’s output—for example, "Unable to find any path."

### How the AI ​​"uses other mods"
- `run_command` — client-side mod commands (Fabric) are executed locally, while all other commands are sent to the server.
- `mod_chat_command` — commands intercepted from the chat (e.g., Meteor’s `.` or Baritone’s `#`); these only function if the corresponding mod is installed (`modChatPrefixes`), ensuring no commands accidentally leak into the public chat.
- `press_keybind` — triggers any keybind defined in the Controls menu (including vanilla and mod-added keybinds, such as those for Xaero’s Map). - `press_key` — simulates a physical key press; useful for mods with their own custom shortcut systems (e.g., Meteor) and for interacting with GUIs.
- `read_screen` / `click_widget` / `type_text` — enables interaction with the screens/interfaces of any mod.
- `baritone` — interfaces directly with the Baritone API (bypassing the chat); it waits for task completion and returns Baritone’s output. Supports all commands available in the installed version of Baritone: `help` / `help <command>` (descriptions pulled directly from the JAR file), `settings <phrase>` / `get <name>` / `set <name> <value>` (on-the-fly configuration), waypoint management (`sethome`, `home`, `waypoints save user <name>`, `waypoints goto <name>`), as well as `follow`, `explore`, `farm`, `tunnel`, `pickup`, `surface`, etc. Continuous commands (such as `follow`, `explore`, `farm`, or `elytra`) do not block execution by default. Note: `goto x y z` means "stand on this specific block"—the mod locks the target to the full block (preventing Baritone from attempting to mine it).

## Context, Cache, and Inference

**`agent.contextMode = "fresh"` (default mode).** With each step, the system sends the following: the same system prompt, tool schemas, and history (which remain byte-for-byte identical, allowing the server to store them in the KV cache), followed by **a single** reconstructed message. This message contains the current command, model notes (`task_notes`), the pinned `plan`, a one-line log of completed actions, the full results from the previous step, and the current game state. The context size does not grow with each step; only the final portion (the "tail") is recalculated. The game state and chat history are no longer embedded in the system prompt, allowing the cache to persist across requests. Benchmarks (using Ollama with a prompt of ~10.5k tokens) show an initial prefill time of ~1.9s, with subsequent steps taking ~0.3s each. Prefill times can be viewed in the logs (`[llm] step N: prompt … tok, prefill … ms`) or in the chat interface by enabling `chat.showTokenUsage`. The legacy mode, where the conversation grows cumulatively, is referred to as `"append"`.

**Ollama (server).** Key environment variables include: `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q4_0` (or `q8_0`—both use less memory than `f16`, with `q4` requiring roughly half the memory of `q8`), and `OLLAMA_NUM_PARALLEL=1` (a single slot ensures consistent prefix cache hits). Additionally, set `keep_alive` within the profile's `extraBody` (e.g., `"30m"`) to prevent the model from being unloaded between requests. The `num_ctx` parameter is handled automatically by the mod, based on the `contextTokens` defined in the profile.

**Speculative decoding.** As of version 0.40, Ollama supports draft models (specified via `DRAFT` in the `Modelfile`) only when using the MLX runner with `safetensors` models; this feature is not currently supported for GGUF models running on CUDA or ROCm. For GGUF, use `llama-server` from llama.cpp with an OpenAI-compatible profile:
```
llama-server -m Qwen3.8-27B-Q4_K_M.gguf -md Qwen3.5-0.8B-Q8_0.gguf --draft-max 16 --draft-min 2 \
-ngl 99 -ngld 99 -fa on -ctk q4_0 -ctv q4_0 -c 131072 --cache-reuse 256 --jinja --port 8080
```

The draft model must use the same tokenizer (from the same family—for example, a small Qwen model for a large Qwen model). In `config.json`, the profile should be configured as follows: `"type": "OPENAI"`, `"baseUrl": "http://localhost:8080/v1"`, `"model": "qwen"`, and `"toolMode": "NATIVE"`. The `--jinja` flag is required for native tool calls, while `--cache-reuse` enables the reuse of the prefix cache.

## Automatic Triggers (disabled by default)

The AI ​​can respond without any input from you—triggered by chat messages that match specific regex patterns (`triggers.chatPatterns`), player death, a player joining the server, or at set intervals (every N seconds). Use `/llm triggers on|off` to toggle, or `/llm triggers world on|off|inherit` to configure settings for a specific world. Messages from other players are passed to the model as untrusted data (`<untrusted>`), and the AI’s own messages will not trigger further responses.

## Commands
```
/ai <message>
/llm stop | status | test | tools | reload | enable | disable | folder
/llm profiles | profile <name> | model <model> | key <profile> [key]
/llm perms | perm <category> <off|ask|allow> [world|singleplayer|multiplayer|all]
/llm memory [world|global] [add <text> | remove <id> | clear]
/llm history show | clear
/llm instructions
/llm triggers on|off | triggers world on|off|inherit
/llm world [enable | disable | profile <name|default>]
/llm config get [path] |
/llm config set <path> <value>
`/llm config set` modifies **any** field, e.g.:
`/llm config set chat.promptPrefix @bot`, `/llm config set profiles.ollama.model gemma4:e4b`, `/llm config set multiplayerPermissions.CHAT_SEND OFF`, `/llm config set agent.language polish`.
```
Full settings screen: Mod Menu → LLMCraft (requires Cloth Config).

## Security — read before playing on servers
- Player automation (movement, mining, Baritone, triggers) may violate server rules or trigger anti-cheat systems. The decision is yours—which is why the default setting for multiplayer is `ASK`.
- Other players might attempt to control the AI ​​via chat (e.g., "AI, type /kill"). While the prompt instructs the AI ​​to treat such input as data, smaller models can be vulnerable—keep `commands`/`chat_send` set to `ASK` when on public servers.
- Limits: minimum interval between messages, maximum number of messages per request, maximum length, and removal of prohibited characters (to prevent being kicked).
- The `config/llmcraft/config.json` file (containing API keys) and any files with names like *token*, *account*, or *secret* are not shared with the model; tokens are also stripped from logs.

## Addon: LLMCraft Bridge (Meteor Client + JsMacros CE)
A separate mod (`bridge/`, `llmcraft-bridge-1.0.0.jar` file); requires LLMCraft ≥ 1.4.0. Works with Meteor alone, JsMacros alone, both, or neither—each integration activates only if the respective mod is installed. For JsMacros, the **Community Edition** (`jsmacrosce`) is required; the original JsMacros (`xyz.wagyourtail`) is not available for 1.21.11.

### Meteor Client
**AI → Meteor** — the `meteor` tool (accessed directly via the Meteor API, rather than through the chat, ensuring the model receives actual error messages):
- `modules` (list; `filter` = keyword, category, or `active`), `info` (description and all settings, including current and allowed values).
- `enable` / `disable` / `toggle`, `set` (module + setting + value in Meteor format), `reset` (reverts a setting to its default).
- `commands` (list of commands; `filter` = command name → full syntax), `command` (any Meteor command without the prefix, e.g., `friends add Steve`, `macro <name>`, `waypoint list`…; invalid syntax returns the usage instructions).
- Permissions: read access (`modules`, `info`, `commands`) = `mods`; modifications = `client_commands` (default: `ASK`). Modifying modules in multiplayer carries an anti-cheat risk—keep this set to `ASK`.
- Permanently blocked: `.reset` and `.swarm` (listed in `bridge.json`), `toggle all`, the AI's own `.ai`/`.llm` commands, and `.say` (which sends a raw chat packet, bypassing `send_chat` limits—the model should use `send_chat` instead; the same applies to `mod_chat_command`). Modules that the AI ​​is prohibited from interacting with are defined in `meteor.blockedModules`. **Meteor → AI**:
- `.ai <message>` (= `/ai`), `.llm <…>` (= `/llm`: `.llm stop`, `.llm perms`, `.llm profile claude`…).
- **LLMCraft** module category: `llm-triggers` (automatic triggers — the same toggle as `/llm triggers`, so it can be bound to a key in Meteor) and `llm-guard` (disables specified modules while the AI ​​is active—by default, Freecam and AutoWalk, which take control of movement—and re-enables them once finished).
- Starscript variables for the HUD (*Text* element): `{llm.status}`, `{llm.busy}`, `{llm.enabled}`, `{llm.profile}`, `{llm.model}`, `{llm.reply}` (the latest response), `{llm.tool}` (the latest tool used), `{llm.tokens}`.

### JsMacros CE
**AI → macros** — `jsmacros` tool:
- `list` (macros with a keybind/event, services, script files, currently running scripts), `read` (script code — the model examines what the macro does; files with names like *token/account/secret* are hidden, similar to `read_config_file`),
- `run` (executes a script from the macros folder and waits; the script receives `args` and returns a result via `LLM.setResult(x)`; the model can see the result, any errors, and anything the script outputs via `Chat.log`), `running`, `stop` (`all` = stops all scripts launched via
- AI),
- `event` (triggers a custom event, i.e., macros bound to it; built-in JsMacros events cannot be spoofed), `enable` / `disable` (macro by file), `service_start` / `service_stop` / `service_restart`.
- Permissions: read = `mods`, everything else = a new category `scripts` (defaults to ASK). **Ending the session / running `/llm stop` also terminates scripts launched by the AI.**
- `code` — the AI ​​writes and executes its own code. **Disabled by default** (`jsmacros.allowInlineCode` in `bridge.json`); even when enabled, every execution requires your explicit consent (including when using "ALLOW" or "Always this session"). JsMacros code has full access to the game and files—including API keys stored in `config.json`.

**Macros → AI** — the `LLM` library available in every script:
```js
LLM.submit("mine 10 iron ore")                               // like /ai, non-blocking
LLM.ask("what should I build?", JavaWrapper.methodToJavaAsync((reply, error) => Chat.log(reply ?? error)))
const r = LLM.askAndWait("where is the nearest village?")    // blocks only this specific script
LLM.runTool("craft", '{"item":"stone_pickaxe"}')             // any LLMCraft tool (bypasses the model)
LLM.runToolAsync("mine", '{"block":"iron_ore","count":5}', JavaWrapper.methodToJavaAsync((text, isError) => Chat.log(text)))
LLM.isBusy(); LLM.status(); LLM.stop();
``` LLM.tools()
if (LLM.isAiCall()) LLM.setResult("done: " + LLM.getArgs())   // script triggered by AI
```
- Requests originating from scripts are marked with the source `SCRIPT`: they appear as `[script]` in the history. Furthermore, the model can only attack players if you explicitly command it to do so (this prevents the model from being tricked into attacking players via a macro bound to the chat).
- `runTool` bypasses confirmation prompts (since it is your own script), but categories set to "OFF" remain disabled, and tool restrictions still apply.
- `askAndWait` does not function within the game thread (e.g., in "joined" macros) or within a script that the AI ​​is currently awaiting (as both scenarios would lead to a deadlock); in such cases, it immediately throws an exception with an explanatory message.

**Events for macros** (selectable in the JsMacros GUI, just like any other event): `LLMRequest` (`prompt`, `source`), `LLMReply` (`text`), `LLMTool` (`tool`, `summary`, `result`, `error`), `LLMDone` (`prompt`, `source`, `reply`, `outcome`: done/error/cancelled), `LLMStop` (`reason`), and `LLMCall` (for scripts triggered by the AI). Example of a macro for `LLMDone`: `Chat.toast("AI", event.getString("reply"))`. ### `config/llmcraft/bridge.json`
```json
{
"meteor":   { "blockedCommands": ["reset", "swarm"], "blockedModules": [] },
"jsmacros": { "allowInlineCode": false, "runTimeoutSeconds": 60, "askTimeoutSeconds": 300, "fireEvents": true }
}
```

## For Addon Developers

```java
ToolRegistry.register(Tool.builder("my_tool", Category.MODS, "Description for the model")
.param("x", "integer", "…", true)
.sync((ctx, args) -> ToolResult.ok("result"))
.build());
```

- Tools registered via `ToolRegistry.register` persist after LLMCraft initialization, regardless of mod loading order.
- `.alwaysAsk((ctx, args) -> …)` — defines calls that the player must always approve (even when set to ALLOW).
- `LlmCraftApi.ask(prompt, Agent.Source.SCRIPT)` → returns a `CompletableFuture<String>` containing the response; also available: `LlmCraftApi.runTool(name, args)`, `busy()`, `status()`, and `stop()`.
- `LlmCraftEvents.register(listener)` — supports events such as `requestStarted`, `toolCalled`, `reply`, `requestFinished`, and `cancelled` (use the latter to halt any processes initiated for the AI).
- `ChatLog.captureAll(true/false)` — temporarily captures every line from the HUD chat (e.g., script output), not just those from `[Baritone]` or `[Meteor]`.

For a complete example, see the addon in `bridge/`. ## Building and Testing

```
./gradlew build               # JAR file in build/libs/
./gradlew test                # Provider format tests (live, using a local Ollama instance if running)
./gradlew runClientGameTest   # Test within an actual Minecraft client (opens a game window)
./gradlew :bridge:build                                       # Add-on: bridge/build/libs/llmcraft-bridge-1.0.0.jar
./gradlew :bridge:runClientGameTest                           # Add-on in a client with Meteor and JsMacros (+ Ollama, if running)
./gradlew :bridge:runClientGameTest -PnoMeteor -PnoJsMacros   # Without one or both mods
```

Test variables: `LLMCRAFT_TEST_MODEL` (defaults to `qwen3.5:4b`), `LLMCRAFT_TEST_OLLAMA`.
