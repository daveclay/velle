# Examples

Each folder here is one example system: the spec, `<system>/<system>.velle`; its generated module, `<system>/output/`; and `<system>/app/`, a Spring Boot application that serves it over PostgreSQL.

## Running one

```
./gradlew :examples:billing:output:generate           # regenerate one example's surface and specs
./gradlew generate                                    # regenerate every example
./gradlew :examples:billing:app:bootRun               # the billing application over PostgreSQL (starts it in Docker); console at http://localhost:8090/velle
./gradlew :examples:pharmacy:app:bootRun              # the pharmacy beside Authentik, with ninety days of test data; console at http://localhost:8093/velle, sign in as margaret
./gradlew :examples:vendors:app:bootRun               # the vendors catalogue beside Authentik; console at http://localhost:8096/velle, sign in as grace or linus
```

`./gradlew :examples:billing:output:compareChanges` writes the reports in `examples/billing/changes/`, a history of changes to the billing spec with what each one does to the business (its `README.md`).

Any other example runs the same way: `./gradlew :examples:<system>:app:bootRun`. The pharmacy's application loads ninety days of test data into an empty database.

## Ports

Each example's application listens on its own port, so several run at once:

| application | port | application | port |
|---|---|---|---|
| batches | 8081 | partition-drift | 8089 |
| bookings | 8082 | billing | 8090 |
| enrollment | 8083 | payments | 8091 |
| gallery | 8084 | peptides | 8092 |
| loyalty | 8085 | pharmacy | 8093 |
| membership | 8086 | platelayout | 8094 |
| moderation | 8087 | shipping | 8095 |
| orders | 8088 | vendors | 8096 |

## Signing in

The pharmacy and vendors specs declare who may act and who may see. Both applications sign people in at Authentik, an identity provider that `bootRun` starts beside the database (`examples/pharmacy/app/compose.yaml`, `examples/vendors/app/compose.yaml`). Opening the console sends the browser to Authentik's sign-in page, and the spec decides what the person who signed in may do and see. `/logout` signs out of both, so the next sign-in can be someone else. On 2026-11-22, with the images already pulled, the first vendors `bootRun` took 55 seconds to answer, Authentik's first start included, and a second one 6 seconds while Authentik kept running.

- Pharmacy (Authentik at http://localhost:9093): sign in as `margaret` (an administrator), or as any other member of staff by first name in lower case, such as `priya` (a pharmacist) or `tom` (a technician), password `pharmacy-demo`. The staff are listed in `examples/pharmacy/app/src/main/kotlin/velle/pharmacy/Roster.kt`.
- Vendors (Authentik at http://localhost:9096): sign in as `grace` (an administrator) or `linus` (staff assigned to Acme), password `vendors-demo`.

Authentik's own admin interface is at its root page, http://localhost:9093 for the pharmacy and http://localhost:9096 for vendors. Sign in there as `akadmin`, password `pharmacy-admin` or `vendors-admin`. That account manages Authentik itself and is not a member of staff in either spec.

To call the API from a script, get a token for a person from Authentik with their app password, which is their user name followed by `-app-password`, then send it:

```bash
token=$(curl -s http://localhost:9096/application/o/token/ -d grant_type=client_credentials -d client_id=vendors -d client_secret=vendors-demo-secret -d username=grace -d password=grace-app-password -d scope=openid | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
curl -H "Authorization: Bearer $token" 'http://localhost:8096/api/shapes/Product?orderBy=name'
```

For the pharmacy, the same with port 9093, `client_id=pharmacy`, `client_secret=pharmacy-demo-secret`, and a pharmacy user name, against http://localhost:8093.
