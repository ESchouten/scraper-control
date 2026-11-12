# Lely Discovery driver: protocol and behaviour

The driver is `src/` (C++17). The M5Stack uses it directly, the apps (JNI and Kotlin/Native) and
Python (ctypes) through the generic C API `core/scraper_c.h`, which `src/scraper_c.cpp`
implements. Link: "ble-serial". Tested against the simulated robot (`tests/fake_robot.py`) with
`tests/test_core.py` (the driver directly) and `tests/test_api.py` (through Python). Everything below
was checked on a real Discovery (November 2026), unless stated otherwise.

## 1. API (the same names in every language)

| Python | C++ (core) | What |
|---|---|---|
| `Robot(pin, address=None, name=None)` | `Robot(transport, encryptPin(pin), imageVersion)` | the robot object |
| `await connect()` | `connect()` | connect, PIN, image version, wait for the screen |
| `status()` | `status()` | schedule, clock, next ride, route, action, ride no., battery (mV), `busy`, `driving`, `stopQuestion` |
| `await start_route("A", dry_run=False)` | `startRoute('A', dryRun)` | start a combined route A–D |
| `await stop_route()` | `stopRoute()` | emergency stop of a running route (only on the driving screen) |
| `await answer_stop(finish)` | `answerStop(finish)` | the user's answer to "finish the route?" after a stop |
| `await set_planning(True)` | `setPlanning(true)` | schedule (time planning) on/off |
| `await press("enter")` | `press(Key::Enter)` | one panel key |
| – (M5, apps) | `enterManual()` / `manualKey(Soft1..3)` / `driveBegin()` · `driveHold(ms)` · `driveEnd()` / `exitManual()` | manual driving, see below |
| – | `readRoutes(out)` | what routes A–D consist of (only reads) |
| (in `disconnect()`) | `finish()` | leave the robot as it was found: back to the home screen from a safe menu, schedule as at connect (unless `set_planning` changed it on purpose) |
| (after every command) | `owedPlanning()` / `setOwedPlanning(v)` | if the link drops before `finish()` could run, the host keeps what the robot is still "owed" (schedule 1/0, -1 = nothing) and hands it to the next session; `connect()` then first puts the robot back. While the robot drives or shows another screen the value stays owed. Apps: per robot, at most 6 hours old; M5: in memory, 6 hours |
| `await disconnect()` | `disconnect()` | |
| `LelyBusy` / `LelyError` | `Result::Busy` / `Result::Unexpected` / … | errors |

Safety rules (the same in every implementation):

0. Screens are recognised by their **shape and positions**, not by words, so it works in every
   language of the robot (Dutch and English recorded, `tests/captures/`). See §4.
1. Keys are only pressed on the **home screen** or in the **route menu** (and, to get back, in the
   main menu: up to the top row, Enter). Any other screen (for example the driving screen) →
   `Busy`, nothing pressed.
   One exception, the **emergency stop** (`stop_route`): on the driving screen one tap on
   **Start/Stop**, as on the panel. A route button during a ride (M5, app, widget) does this
   instead of starting. The robot stops at once and asks **"Route stopped: finish the route?
   Yes / Pause / No"** (§4). That question is **never answered automatically**, because Yes
   means the robot drives on. The M5 and the apps show it and pass the user's answer on with
   `answer_stop`: Yes (Soft1) = the route goes on, No (Soft3) = the route ends and the robot
   leaves the schedule off. The middle choice (a submenu with manual and a pause in minutes)
   is not offered.
2. After every key, wait for the **expected screen** (not for a fixed time).
3. If a step before Start does not show the expected screen: back to the home screen, schedule
   back as it was, report the error.
4. After Start: only the schedule question is answered; if it does not appear, nothing more is
   pressed and `StartedNoAnswer` is reported (the schedule is then OFF).

### `start_route(letter)` step by step
1. Home screen? (otherwise `Busy`). Read the schedule from the top line (`20:21:19  ON`).
2. Schedule ON → **Start/Stop** → wait until the top line shows `OFF`.
3. **Enter** → wait for the route menu, rows `A ->  1  -  -` … `D -> …`.
4. Read the selected row (inverted); **Down/Up** until `letter ->` is selected.
5. **Start/Stop** → wait for the question "schedule on after this route?".
6. Schedule was ON → **Soft1** (left, "Yes"); it was OFF → **Soft3** (right, "No").
7. The screen becomes the driving screen. Done.

The robot only leaves the home screen with the schedule **OFF** (Enter and Esc do nothing
otherwise).

### Manual driving
1. Home screen, schedule OFF (remember whether it was ON) → **Enter** (route menu) → **Enter**.
2. Safety question ("manual operation is only allowed with sight of the vehicle. Confirm?") →
   **Soft1** = Yes (only at the request of someone who sees the robot), Soft3 = No.
3. Manual screen: **Soft1** left motor on/off, **Soft2** forward/backward, **Soft3** right motor
   on/off (settings, nothing moves). On entry: L off, R off, forward.
4. **Hold Start/Stop = drive**: key event 0 (down), then event 1 (repeat) every 100 ms while held,
   event 7 (up) = stop. **When the connection drops while driving, the robot stops at once and turns its motors off** (tested 2026-11-11); it stays on the manual
   screen.
5. Back: Esc → route menu → Esc → home screen, schedule back as it was.

## 2. Transport

* BLE, Nordic UART Service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; write to RX `…0002…` (write
  without response), notifications from TX `…0003…`.
* The robot advertises with its name (set by the farmer), **without** the service UUID in the
  advertisement. The MAC address starts with `00:12:FF` (Lely's IEEE prefix). On macOS you only
  see a UUID; search by name there.
* One connection at a time: close other apps connected to the robot.
* Timing (Mac): BLE connection ~1–2.8 s, access +0.3 s, robot ready +0.5 s, first screen text
  ~1 s later.

## 3. Messages (LFW)

Little-endian header of 8 bytes: `msgID u16, address u16, sender u16, length u16`, then `length`
bytes. No checksum.

| Direction | msgID | addr | sender | payload | meaning |
|---|---|---|---|---|---|
| → | 2010 | 57 | 57 | `01 01` | computer type |
| → | 2000 | 57 | 244 | – | ask node settings |
| ← | 2001 | | | `u16 id, u16 sys, name\0, …` | name = the robot's name |
| ← | 2011 | | | `ec 00` | the robot's MTU (236) |
| → | 2018 | 57 | 244 | AES(PIN) | ask for access (empty = no PIN) |
| ← | 2020 | | | `01` / `00` | access yes / no |
| ↔ | 1536 | 245 | 244 | n × 16-byte CAN records | panel (keys, screen) |

PIN: AES-128-CBC, IV = 16 zero bytes, PKCS7, with a fixed key: the build secret `LELY_APP_KEY`
(`src/LelyPin.cpp`).

### CAN record (16 bytes)
`id u32` (app: 1022 = 0x3FE; robot: 0x3E1), `data[8]`, `length u8`, 3 × 0. Robot records have
`id & 0xF00 == 0x300`. `data[0]` = kind, `data[1]` = sub.

| Direction | data | meaning |
|---|---|---|
| → | `02 06 lvl 00` len 3 + `02 05` len 2 | connect with level (lvl = 1) |
| → | `02 02` len 2 + `02 05` len 2 | connect panel |
| ← | `02 00` | robot asks to reconnect → answer `02 01 00 00` len 3 + `02 05` |
| → | `06 01` len 2 and `06 02 lvl 00` len 3 | keep-alive, every second |
| ← | `05 00` | robot asks for the **image version** |
| → | `05 01 00 v0..v4` len 8 + `05 01 01 v5..v8 00` len 7 | our image version (9 bytes) |
| ← | `05 02 01 N` | "I will send N images" (version unknown or different) |
| ← | `05 06 idx id …` / `05 07 6×data` | image header / image data (~75 s for 52 images) |
| ← | `05 04 00 v0..v4` / `05 04 01 v5..v8` | the robot tells its image version → keep it |
| ← | `05 02 00` | no (more) images → we send `05 03` len 2 ("ready"), the screen follows |
| ← | `03 14 00 00` | asks contrast → `03 14 01 ff` len 4 |
| → | `ev lo, ev hi, key lo, key hi` len 4 | key: ev 0 = down, 7 = up (80 ms apart) |
| ← | `03 op …` | drawing command, see §4 |

The image version is 9 ASCII bytes, for example `DIS0018T4`. Sending the version the robot has
makes it skip the images (connecting takes 3.5 s instead of 80 s). The clients remember it per
robot; after a firmware update of the robot the images come once more, with a new version.

Key codes: Back/Esc 1, Soft2 2, Soft1 4 (left), Up 8, Prog 16, Down 32, Soft3 64 (right),
Enter 128, Start/Stop 256.

## 4. Screen (128×64, monochrome)

| op | data | meaning |
|---|---|---|
| 1 | – | clear the screen |
| 30 | `y x font` | start of text |
| 31 | 6 characters | more text, until a 0 byte. `\r` = next line, 8 px lower |
| 32 | `y x font c` | one character |
| 13 | `y x h w` | clear an area |
| 33 | `y x h w c` | rectangle (outline) |
| 37 | `y x h w c` | **invert (XOR)**: the **selected row** (skip the title bar `1 1 11 126`). The same area again = gone; the main menu sends the old and the new row on Up/Down. A row drawn again (op 30) loses its inversion |
| 36 | `y x nr` | picture from the image set (manual screen: robot nr 13 at 16,49; arrow at 53,58: 16 forward, 15 backward) |
| 15, 34–35, 40–54 | | lines, input dialogs (not needed) |

A text lies in an area when `x ≤ tx < x+w` and `y−2 ≤ ty < y+h`.

Recognition by shape (`Screen::isHome()` etc., works in every language):

| Screen | Shape |
|---|---|
| home screen | top line `hh:mm:ss` + `ON`/`OFF` (y 3) and labels at x 4, y 14…54. Values per **row**: next ride, route, action, action no., ride no., battery (mV) |
| route menu | four rows `A ->` … `D ->` (x 19, y 16…40) |
| main menu | six rows from y 4 at x 0, no title, no clock; the top one is Werken / Work |
| question (yes/no) | bottom row (y 54) with exactly one word left and one right |
| stop question | bottom row (y 54) with three words: left (x 15, Soft1 = Yes), middle (x 49, Soft2 = Pause) and right (x 97, Soft3 = No); a box "HAND" above the middle one |
| manual | arrow picture 15/16 in the middle box at the bottom |
| driving | title with `->` at the top (y ≤ 5), no clock, labels at x 4 (y 14…46): only `stop_route` presses here |
| other | none of the above: nothing is pressed (seen, for example: the charger's manual screen) |

The **robot's language** (`Screen::language()`, `"language"` in the status JSON: `nl`, `en` or
empty) does come from words. It is only used for the language of a user interface (the M5 follows
it); the recognition above does not depend on it. The texts the core makes itself (result, error
detail, log) are Dutch or English: `Robot::lang`, `scraper_set_language(s, "en")`,
`scraper_result_text_lang(r, "en")`.

Screens we know (Dutch robot):
```
Home screen                 Route menu                  Question after Start
20:21:19  ON                HANDM. ROUTE                HANDM. ROUTE
Vlg rit: 21:00:00           A ->  1  -  -   (selected)  Tijdpad na rijden
Route  : 01                 B ->  2  -  -               route aanzetten?
Actie  : ------------       C ->  3  -  -               Ja          Nee
Actienr:     -              D ->  4  -  -
Ritnr  :    10              Reset Save >
Accu   : 13500

While driving               After Start/Stop while driving
ROUTE->TIJDPAD              ROUTE GESTOPT
Route  :     1              Route stop gezet:
Ritnr  :    11              route afmaken?
Actie  : WANDVOLG_DST                [HAND]
Actienr:     1              Ja    Pauze    Nee
```
