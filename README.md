# riffs_server

Part of **[🎸 Riff Rivals](https://github.com/markmckellar/riffs_client_vue)**: hangman for your ears. One player
writes a short riff, the other hears it and has to write it down. Every wrong guess snaps a guitar string.
Play it at **https://riffs.mckcode.com/**. The story behind it is in the
[client's README](https://github.com/markmckellar/riffs_client_vue#why-i-built-it).

This is the server, and it's deliberately small: the phone makes all the sound, draws the staff, listens to the
microphone and fits riffs to each instrument. The server keeps players and games, checks guesses, scores riffs and
runs the bots. A riff is just a list of MIDI pitches. Spring Boot 2.7 / Java 17, data in plain JSON files.

## What's interesting in here

- **A "does this sound musical?" score** (`music/MusicSense`): a riff earns points for fitting a key, moving mostly
  by small steps, rising and falling, repeating a little pattern, and ending on the home note so it sounds finished.
  Tritones, jumps of more than an octave and the same note over and over cost points. Every point comes with a
  reason in plain words, and a high score earns the writer tokens (💡 Hints and 🎸 Extra Strings). Random
  key-mashing scores low.
- **15 bots in three levels** (`game/BotPlayer`) so there's always someone to play. Bots write riffs from a small
  library of public-domain tunes, cut into riff-sized pieces at phrase boundaries (`library/TuneLibrary`,
  `resources/tunes.json`). They guess from the same marks a person sees: each guess fits everything learned so far,
  with the easier bots sloppier and the occasional wild guess so they can lose. Easy bots solve about 46 % of riffs
  before running out of strings, hard ones about 98 %.
- **Guess feedback** per note: exact, right note in the wrong octave, close (with up or down), or miss.
- **Challenges**: a game with a person starts as an invite they accept or decline. Quick match asks someone who's
  online, and a bot steps in after 60 seconds or a no.
- **One JSON file per game**, in a folder per day (`games/<day>/<id>.json`), so a move rewrites only its own game.
  Games in progress and the last 30 days stay in memory; older ones are read from disk when someone asks.
- **A range every instrument can play**: E3 to G♯5, and a riff spans at most 19 half steps, so the client can always
  move it onto a guitar, bass or 12-key piano.

## Run it locally

```bash
./run_dev.bash            # port 9095, sign in by typing a name (no Google), data in local/data/
mvn test                  # musical score, tune library, game rules, bots, storage, guest sign-in
```

Then start the client (`cd ../riffs_client_vue && npm run dev`) and open http://localhost:5175/.

`deploy.bash` builds the server and the client, copies them to the live server and restarts it
(`--client-only` ships a new web client without a restart, so games carry on).

## Settings (`application.properties`, or `--name=value`)

| Setting | Default | |
|---|---|---|
| `riffs.game-name` | `Riff Rivals` | the name players see; the only place it's set |
| `riffs.data-dir` | `local/data` | `users.json`, and `games/<day>/<id>.json` |
| `riffs.games.keep-days` | 30 | finished games older than this are read from disk when needed |
| `riffs.auth.google-client-id` | empty | OAuth web client ID; empty = Google sign-in off |
| `riffs.auth.guest-login` | false | sign in as a new guest account with any name; safe in public |
| `riffs.auth.dev-login` | false | sign in as anyone by typing a name. **Testing only** |
| `riffs.auth.session-key-file` | `local/session.key` | signs the sign-in cookie; made on first start |
| `riffs.bot.min-delay` / `max-delay` | 3 / 8 | seconds a bot "thinks" |

**Google sign-in** (not switched on yet): Google Identity Services button → `POST /api/auth/google` → the token is
checked with Google → a signed HttpOnly cookie. It needs an OAuth **web client ID** with the site's origin under
"Authorized JavaScript origins".

## API

All JSON. Everything under `/api` except auth and health needs the sign-in cookie.

| Method | Path | |
|---|---|---|
| GET | `/api/health` | |
| GET | `/api/auth/config` | game name, Google client id, which sign-ins are on, who is signed in |
| POST | `/api/auth/google` `{credential}` · `/api/auth/guest` · `/api/auth/dev` `{name}` · `/api/auth/logout` | |
| GET | `/api/home` | people online now, and everyone I've played (online, wins/losses, the open game with them) |
| GET | `/api/players?q=name` | find players by name |
| GET | `/api/games` | my games, active first |
| POST | `/api/games` `{opponentId?}` | start a game; no opponent = quick match (someone online, else a bot). With a person it's a challenge (status `invited`) until they accept |
| POST | `/api/games/{id}/accept` · `/decline` · `/bot` | answer a challenge; `/bot`: play a declined one with a bot instead |
| GET | `/api/games/{id}` | the game as I may see it |
| POST | `/api/games/{id}/riff` `{notes:[{m,b}], tuneId?}` | send my riff (m = MIDI pitch, or null for a rest; b = beats) |
| POST | `/api/games/{id}/guess` `{notes:[midi…]}` | guess; each note is marked exact / octave / close (+up/down) / miss |
| POST | `/api/games/{id}/hint` · `/extra-string` · `/resign` | spend a token, or give up |
| POST | `/api/riffs/evaluate` `{notes}` | the musical score with its reasons (nothing saved) |
| GET | `/api/riffs/rules` | note limits, pitch range, strings |
| GET | `/api/library` · `/api/library/{tuneId}/fragments` | the public-domain tunes and their riff-sized pieces |
| GET | `/api/events?after=seq` | what happened since `seq` (the client asks every few seconds) |

## Code map

- `music/`: `Note`, `RiffRules` (4–8 notes, at most 2 rests, E3–G♯5, span ≤ 19 half steps), `MusicSense` (the score)
- `library/`: `TuneLibrary` and `resources/tunes.json`, tunes cut into riffs at phrase boundaries
- `game/`: `Game` (what's stored), `GameService` (rules, rewards, what each player may see), `GameRules` (the
  numbers), `BotPlayer`, the game store
- `auth/`: Google and guest sign-in, the session cookie. `user/`, `events/`, `store/JsonFiles`

## Known limits

- The listener's phone is sent the riff's notes so it can play them, so a player could cheat with developer tools.
- Events are polled and kept in memory; a phone isn't told anything while the page is closed (Web Push is planned).

## License

MIT. See [LICENSE](LICENSE).
