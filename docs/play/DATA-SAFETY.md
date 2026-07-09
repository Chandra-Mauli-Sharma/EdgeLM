# EdgeLM — Play Data Safety Answers

Recommended answers for the Play Console **Data safety** form. EdgeLM collects and
shares no personal data, which makes this section short — but answer every prompt
honestly to match the app's actual behavior.

## Data collection & sharing

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Does your app collect or share personal info, financial info, location, messages, photos, contacts, etc.? | **No** — none of these |
| Is all user data encrypted in transit? | **Yes** (model downloads use HTTPS; the app sends no user data) |
| Do you provide a way for users to request data deletion? | Not applicable — no data is collected. (Select the honest option; there is no account or server-side data.) |

Because EdgeLM collects **no** user data, most of the form collapses to "No data
collected" and "No data shared."

## Key points to keep consistent with the listing

- **No analytics, no ads, no accounts, no crash reporting.**
- **Prompts and responses are processed on-device only** and are never sent to a
  server or stored.
- The **only** network activity is downloading the model file the user chooses, over
  HTTPS. This is not collection of the user's data — it's fetching a public file — so
  it is not declared as data collection.

## Permissions declaration (for the "App access" / permissions review)

| Permission | Why |
|---|---|
| `INTERNET` | Download the user-selected model file |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the shared inference runtime alive while in use, with a visible notification |
| `FOREGROUND_SERVICE_DATA_SYNC` | Keep a model download running if the user leaves the app |
| `POST_NOTIFICATIONS` | Show runtime status and download progress |
| `ai.edgelm.permission.USE_RUNTIME` (custom) | Gate which apps may bind the runtime |

## Foreground service — "special use" declaration

Android 14+ requires a declared type and justification for the `specialUse`
foreground service. Paste this into the Play Console FGS declaration:

> EdgeLM is a shared, general-purpose on-device AI inference runtime. Other apps bind
> to it to run language models locally. The foreground service keeps the shared model
> resident in memory and serves inference requests while apps are actively using it,
> with an ongoing notification showing runtime status. No standard foreground service
> type (camera, location, mediaPlayback, dataSync, etc.) describes a general on-device
> inference runtime, so `specialUse` is declared. Model downloads separately use a
> `dataSync` foreground service.
