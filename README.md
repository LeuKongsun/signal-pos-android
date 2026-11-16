# Signal POS — native Android tablet app

The Android app uses native Android Views and Material components. It does not load the web UI, use WebView, or execute JavaScript. `../web` remains the backend and the functional reference; the Android app calls its JSON APIs.

## Try it on your Redmi Pad 2

Open this directory in Android Studio, choose the tablet, and Run. Or install the debug APK from `app/build/outputs/apk/debug/app-debug.apk` after building:

```sh
./gradlew :app:assembleDebug
```

First launch with no saved server opens a clearly marked demo register using the web project's sample menu. You can test menu search, categories, modifiers, quantity, notes and price edits (long-press an order line), promotions, up to eight held orders, cash/Bakong checkout, receipts, and native print/PDF output. Demo payments stay on this device, separate from real store data, and never sync to the backend.

Landscape shows the menu beside a fixed-width cart; narrower windows place the order below the menu. Layout is based on Android density-independent width, rather than physical pixel dimensions. Rotation preserves the in-memory order, and drafts persist through activity/process recreation. The screen stays awake while the app is visible, and system navigation stays available.

## Connect real store data

Choose **Connect server**. Your deployed backend, `https://signal-pos.vercel.app`, is prefilled; tap **Connect backend** and sign in. Previously saved server addresses are preserved. If that saved store does not answer, the tablet stays on a retry screen and does not open the demo menu. This is the server address for JSON requests, not a website displayed inside the app. No database secrets are stored on the tablet. Production uses HTTPS with a valid certificate; HTTP and certificate bypasses are unsupported. If the backend only runs at `localhost:3000` on your computer, it is not yet reachable by the tablet; use a deployed backend or trusted HTTPS development endpoint.

Staff name entry and PIN login use the existing session APIs. The staff directory is no longer public; this APK does not fetch it before sign-in. Native screens cover:

- Register: live catalog and drink images, modifiers, prices, notes, offers, hold/resume, cash and manually confirmed Bakong payments. Connected checkout uses the store’s USD or KHR currency. The sample menu uses USD.
- Sales: device receipts, pending sync errors, retry, JSON backup export, server history, refunds/voids with manager approval for cashiers.
- Shifts: opening cash, current totals including register cash paid out, counted cash, and closing variance.
- Reports: date ranges and presets, USD and KHR, net collected, returns, payment methods, products, period totals, and CSV export.
- Management: product/category creation, product editing and modifier-group assignment, drink image selection and upload for saved products, staff/PIN management, stock creation/editing, restock/waste/count, paginated stock history, supplier purchases and payments, and recipe creation/import/editing/activation with version checks.

Permissions and stock deductions remain enforced by the backend. The app accepts only JSON responses and refuses redirects, preventing session cookies being forwarded to another origin.

## Durable checkout and offline behavior

After signing in and opening a shift, the app obtains `/api/orders/context`, including the existing signed menu, tax and recipe authorization. It refreshes preparation every five minutes while foregrounded. Checkout uses the signed snapshot and creates the same `LocalSale` payload as the web implementation. Amounts use integer hundredths of the named currency. Percentage discounts and included tax round to whole riel for KHR, matching web checkout. Existing untagged drafts and receipts are treated as USD.

A native SQLite transaction saves the complete immutable receipt and deletes the active draft before the UI clears the cart or reports payment complete. A failed commit preserves the cart. Held/active draft transfers also use transactions. `/api/orders/sync` receives the original UUID and receipt reference on every retry; backend deduplication prevents duplicate sales and stock deductions after a lost response. Only the original staff member's receipts sync. Acknowledgements cannot be reversed by stale errors. Retries back off to 60 seconds and run while the app is foregrounded.

An already-prepared register can check out during a network outage until its authorization expires. Cold start/session verification, signing in, opening shifts, loading current data, and preparation require the backend. Background/terminated sync and fully offline cold start are not implemented. Pending receipts block sign-out, server changes and shift close. A closed shift or rejected authorization leaves the original receipt locally for manager review and backup; receipts are never automatically deleted.

Sync or export before clearing app data or uninstalling. Backups and device transfer deliberately exclude session cookies, drafts and receipts; cloning them onto another tablet could duplicate a register's local state. Export during an Android file-picker process interruption must be retried. Receipt printing uses Android's print service / Save as PDF; direct ESC/POS Bluetooth/USB drivers are not included.

## Validation and current limits

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Unit tests cover decimal money input, promotion rounding, cheapest-unit multi-buy discounts, and included tax. Emulator tests exercise durable outbox storage, owner isolation, acknowledgement monotonicity, duplicate rollback, draft clearing, and native demo checkout through activity recreation.

The Android app offers English and Khmer under **Settings → Language**. Khmer covers navigation and common labels. Some messages and management screens still use English. Reports show collected, returned, and product charts, plus tabular summaries and CSV export. Sales times use the store clock in Phnom Penh, including when the tablet clock is wrong.

The tablet emulator tests use demo data. Authenticated checkout, uploads, and management writes still need validation against a test backend and the actual Redmi before live use.
