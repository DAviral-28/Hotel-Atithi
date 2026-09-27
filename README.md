# Hotel Atithi — Booking Platform

Full-stack serverless booking system for **Hotel Atithi**, a 32-room hotel on Station Road,
Gular Naka, Banda, Uttar Pradesh (near Banda Railway Station). Built to run entirely on the AWS
Free Tier in `ap-south-1` (Mumbai) — no intended recurring hosting cost. See `progress.md` for
the full architecture and decision log.

- `backend/` — Java 21 + Spring Boot 3 REST API, packaged as an AWS Lambda (via AWS Serverless
  Java Container), backed by DynamoDB.
- `frontend/` — React 18 + Vite + TypeScript + Tailwind CSS single-page app, deployed to
  S3 + CloudFront.
- `infrastructure/` — AWS CDK (TypeScript) defining the DynamoDB table, Lambda, API Gateway,
  S3 bucket and CloudFront distribution.

## Prerequisites

- Java 21 and Maven (or use the included `backend/mvnw` wrapper — no separate Maven install
  needed)
- Node.js 18+ and npm
- An AWS account and credentials configured locally (`aws configure`) if you intend to deploy
- The [AWS CDK CLI](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html) (installed as
  a dev dependency of `infrastructure/`, runnable via `npx cdk`)

## Running the backend locally

`./mvnw test` only runs the unit tests and then exits - it never starts a server. To start the API:

```
cd backend
# no AWS account needed: bookings are kept in memory (lost when you stop it)
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"      # Windows PowerShell
./mvnw spring-boot:run -Dspring-boot.run.profiles=local        # macOS / Linux
```

It stays running on `http://localhost:8080` (press Ctrl+C to stop); the frontend's `npm run dev` proxies
`/api` to it. Staff console PIN locally is `1439` unless you set `HOTEL_STAFF_PIN`.

Without `-Dspring-boot.run.profiles=local` the backend talks to the real DynamoDB table (set `AWS_REGION` /
`TABLE_NAME` and have valid AWS credentials); with no credentials every booking endpoint fails.

Run the test suite:

```
./mvnw test
```

## Running the frontend locally

```
cd frontend
npm install
npm run dev
```

Starts on `http://localhost:3000` and proxies `/api` requests to `http://localhost:8080` (see
`vite.config.ts`). If the backend isn't reachable, the room list falls back to a local, static copy
of the 32-room catalog (static tariffs only), but **nothing about bookings is ever simulated**: live
availability, holds, confirmations, payments, extensions and cancellations all come from the server.
If the server can't be reached the page shows a clear error banner with a "Try again" button and
rooms are marked "Not checked" until the server answers. There is no offline/fake booking mode.

## Deploying to AWS

**Guest PII (name/phone/email) is encrypted at rest, and booking-confirmation tokens are
cryptographically signed — but both use hardcoded development keys unless you configure real
ones.** Deploying without setting the two variables below will fail on purpose (see
`infrastructure/lib/hotel-infra-stack.ts`), rather than silently shipping a Lambda that anyone
with the source code could use to decrypt guest data or forge booking tokens.

Generate real keys once and keep them somewhere durable (a password manager or your CI's secret
store) — every future deploy needs the *same* encryption key, or every previously stored
booking's PII becomes permanently undecryptable:

```
cd backend && ./mvnw clean package        # produces target/hotel-backend-1.0.0.jar
cd ../frontend && npm install && npm run build   # produces dist/

cd ../infrastructure
npm install
HOTEL_ENCRYPTION_KEY_256=$(openssl rand -base64 32) \
HOTEL_SIGNING_KEY_256=$(openssl rand -base64 48) \
npx cdk deploy
```

`cdk synth` / `cdk deploy` also **refuse to run if `backend/target/hotel-backend-1.0.0.jar` is missing,
empty or not a real JAR** — otherwise a broken, placeholder Lambda would be deployed "successfully".
Build the backend first (first command above). For a deliberate bootstrap deploy before the backend
exists you can opt in with `npx cdk deploy --context allowPlaceholder=true`; the API will not work
until you rebuild the JAR and deploy again.

After deploy, `cdk deploy` prints the CloudFront URL (`WebsiteURL` output), the API Gateway
endpoint (`ApiGatewayEndpoint` output) and the website bucket (`WebsiteBucketName` output).

### Pointing the frontend at the API (the `/prod` stage)

API Gateway serves this API under a **stage** named `prod`, so every request must go to
`https://<id>.execute-api.ap-south-1.amazonaws.com/prod/api/...`. The CDK output looks like:

```
ApiGatewayEndpoint = https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod/
```

Set `VITE_API_BASE_URL` to that value **including `/prod`, but without the trailing slash**:

```
VITE_API_BASE_URL=https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod
```

The frontend appends `/api/...` to it. If you leave `/prod` off, calls go to
`.../api/...` at the API root and API Gateway answers **403 `Missing Authentication Token`**.
(A trailing slash is harmless — the client strips it.)

Then build and upload the site:

```
cd frontend
VITE_API_BASE_URL=https://abc123xyz.execute-api.ap-south-1.amazonaws.com/prod npm run build
aws s3 sync dist/ s3://<WebsiteBucketName> --delete
```

On Windows PowerShell, set the variable first: `$env:VITE_API_BASE_URL="https://.../prod"; npm run build`.

### Payments (zero cost) and the GST invoice

There is no payment gateway, so there are no transaction fees. Guests choose at confirmation time:

- **Pay at Hotel** — the booking is `CONFIRMED` with `paymentStatus = DUE_AT_CHECKIN`; the slip says
  "Payment Due at Check-in".
- **UPI QR** — the page shows a QR / `upi://pay?...` link for the exact amount; the guest pays the
  hotel's UPI ID and types the 12-digit UTR. The booking is `CONFIRMED` with `PAID_ONLINE`.
  **The UTR is self-declared** (nothing calls back from the bank), so the front desk must check each
  UTR against the UPI statement. Each UTR can be used only once.

Configure the UPI ID at deploy time (leave unset to offer Pay at Hotel only):

```
HOTEL_UPI_VPA=hotelatithi@okhdfcbank HOTEL_UPI_PAYEE_NAME="Hotel Atithi" npx cdk deploy
```

(For local runs set the same variables before `./mvnw spring-boot:run`.)

The printed invoice shows the hotel's legal name and GSTIN. These are the hotel's own registered
details, so set them when building the frontend; the GSTIN line is left out if unset:

```
VITE_HOTEL_LEGAL_NAME="Hotel Atithi (Near Railway Station, Banda)" VITE_HOTEL_GSTIN=<your GSTIN> npm run build
```

## Front-desk staff console (`/staff`) and guest notifications

Reception opens **`/staff`** on the website (there is also a discreet "Staff" link in the footer). It is
protected by one shared PIN, so there is nothing extra to pay for or sign up to:

```
HOTEL_STAFF_PIN=<6+ digits> npx cdk deploy        # local run: export HOTEL_STAFF_PIN before ./mvnw spring-boot:run
```

- If `HOTEL_STAFF_PIN` is not set the PIN is **`1439`** (fine for a first test, not for real use; the
  backend logs a warning and CDK prints a synth warning). Changing the PIN signs everyone out.
- `POST /api/staff/login` exchanges the PIN for a session token valid for 10 hours (configurable 8-12
  via `hotel.staff.token-hours`), sent as `X-Staff-Token` (or `Authorization: Bearer`). Login is limited to
  5 attempts per IP per 5 minutes. Guest booking tokens do not work on staff endpoints and vice versa.
- Console: live stats, arrivals / in-house / departures with one-click **Check In**, **Collect ₹X** and
  **Check Out**, a colour-coded 32-room grid (green available, blue reserved, purple in-house, amber
  maintenance), room maintenance blocking, a walk-in booking form and the list of UPI payments to verify.
- Staff endpoints: `GET /api/staff/dashboard`, `POST /api/staff/bookings/{id}/check-in`, `/check-out`,
  `/verify-payment`, `POST /api/staff/rooms/{n}/block` (`{"startDate","endDate"}`, inclusive nights),
  `/unblock`, `POST /api/staff/walkin`.

**WhatsApp confirmation (no setup, free):** the confirmation slip has a *Send confirmation to WhatsApp*
button that opens `https://wa.me/91<guest phone>?text=...` with the booking details pre-filled; the
desk gets the same button after a walk-in.

**E-mail confirmation (optional, AWS SES free tier):** set `AWS_SES_SENDER_EMAIL` to an address or
domain you have verified in SES (`ap-south-1`) and CDK grants the Lambda `ses:SendEmail`:

```
AWS_SES_SENDER_EMAIL=bookings@yourdomain.in npx cdk deploy
```

Without it nothing is sent (the backend only logs) and bookings are never affected. While the SES
account is in the sandbox it can only e-mail verified addresses; request production access (free) in
the SES console to reach any guest.

## Booking rules enforced by the API

- Stays are 1-30 nights; check-in can be at most 365 days ahead (violations return `400` with a plain-language message).
- All "today" / date decisions use **India time (Asia/Kolkata)**, both in the backend clock and in the frontend.
- A hold lasts 10 minutes. `DELETE /api/bookings/{id}/hold` (with the `X-Booking-Token` header from the hold response) releases it immediately.
- `POST /api/bookings/{id}/confirm` is idempotent: repeating it with the same `paymentReference` returns the already-confirmed booking.
- Booking records and their room-night rows are kept for 90 days after the check-out day, then removed by DynamoDB TTL.
- `POST /api/bookings/{id}/confirm` takes `{"paymentMethod": "PAY_AT_HOTEL" | "UPI_QR", "paymentReference": "<12-digit UTR>"}`; the response carries a `manageToken`.
- Cancel / extend / pay-balance need that token in `X-Booking-Token` (`POST /{id}/cancel`, `/{id}/extend`, `/{id}/pay`). A guest who has lost it gets a new one with `POST /{id}/access` `{"guestPhone": "..."}`.
- Cancellation is free until 24 hours before check-in (12:00 PM IST). A later cancellation needs `{"acknowledgeLate": true}` and is flagged `lateCancellation`; it is refused once check-in time is reached. Cancelling is idempotent.
- Extending a stay adds the extra amount to `totalAmount` and sets `paymentStatus = DUE_AT_CHECKIN` until it is paid at the desk or via `POST /{id}/pay`.

## Known limitations

- The staff PIN is one shared secret (no per-person accounts or audit trail of who clicked what).
- There's no key-rotation tooling — see the deploy note above.
- UPI UTRs are entered by the guest and are not verified automatically (no payment gateway); staff
  reconcile them against the UPI statement in the staff console (**UPI to verify** tab). Refunds for cancelled prepaid bookings are also
  processed manually by the hotel.
- Viewing a booking by its ID (`GET /api/bookings/{id}`) needs no login (IDs are unguessable UUIDs);
  changing it (cancel / extend / pay) needs the manage token, obtained at confirmation or by proving
  the guest's phone number.
