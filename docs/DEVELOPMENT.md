# Local development

[Project overview](../README.md) · [Operator guide](OPERATING.md) · [Verification](VERIFICATION.md)

Run commands from the repository root unless a different directory is shown.

## Choose a runtime

| Runtime                      | Requirements                      | Use                                                                      |
| ---------------------------- | --------------------------------- | ------------------------------------------------------------------------ |
| Docker Compose               | Running Docker engine and Compose | Run all three services with one command.                                 |
| Local Java + Docker database | JDK 21+, Node 22.12+, Docker      | Edit/debug Java or use frontend hot reload. Maven is provided by `mvnw`. |

The [quick start](../README.md#run-locally) runs the whole application in Docker. The local-development commands below run the Java processes on your host and PostgreSQL in Docker.

## Build and run Java locally

Finish or resolve active work before switching runtimes. If the Docker ground and simulator services are running, stop those two services to avoid competing runtimes and a port-8081 conflict:

```sh
./scripts/compose.sh stop ground simulator
./scripts/compose.sh up -d --wait database
./scripts/build.sh
```

Start each process in a separate terminal, from the repository root:

```sh
# Terminal 1: independent spacecraft
java -jar target/apogee-0.1.0.jar --simulator
```

```sh
# Terminal 2: ground service and web interface
java -jar target/apogee-0.1.0.jar
```

Open [localhost:8081](http://127.0.0.1:8081). The build script cleans the Java output, installs frontend dependencies, builds the web assets, copies them into the JAR, and packages Java. It skips tests; use the [verification commands](VERIFICATION.md#run-the-checks) separately.

Stop local Java processes before rebuilding the JAR they use. To return to Docker, stop both local processes, then run `./scripts/compose.sh up --build -d`.

## Frontend hot reload

With the ground service running on port 8081:

```sh
npm --prefix web ci
npm --prefix web run dev
```

Open the URL printed by Vite. Its development server proxies `/api` to the ground service. In the packaged application, Spring Boot serves the frontend directly; Node is only needed for the build or development server.

## Ports and stored data

| Component                      | Local address / port                                      | Persistence                                                      |
| ------------------------------ | --------------------------------------------------------- | ---------------------------------------------------------------- |
| Ground HTTP and built frontend | `127.0.0.1:8081`                                          | Uses PostgreSQL; browser state is not authoritative.             |
| PostgreSQL through Compose     | `127.0.0.1:55432` → container `5432`                      | `postgres-data` named volume.                                    |
| Local Java simulator           | `127.0.0.1:7071`                                          | `data/spacecraft.json`.                                          |
| Docker simulator               | Internal service `simulator:7071`; no published host port | `simulator-data` named volume, file `/app/data/spacecraft.json`. |

The Docker and host simulators have separate ledgers. Switching between them does not transfer spacecraft state, even when the ground service uses the same database. Use a consistent runtime for a recovery experiment; a missing ledger cannot establish old command outcomes.

`stop`/`start` preserves Compose data. Removing volumes deletes stored history or spacecraft evidence. The app defaults to loopback access and has no public user authentication.

## Configuration

[application.properties](../src/main/resources/application.properties) supplies local defaults. Compose overrides the database and simulator hostnames for container networking.

| Environment variable                                 | Purpose                                                                  |
| ---------------------------------------------------- | ------------------------------------------------------------------------ |
| `PORT`, `BIND_ADDRESS`                               | Ground HTTP port and listen address.                                     |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL connection settings.                                          |
| `SIMULATOR_HOST`, `SIMULATOR_PORT`                   | Ground-to-spacecraft address; the simulator also reads `SIMULATOR_PORT`. |
| `SIMULATOR_BIND`, `SIMULATOR_STATE`                  | Simulator listen address and state-file location.                        |

For Colima, Testcontainers may need these settings in the terminal running Maven:

```sh
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Changing only the Compose project name does not change published ports. Concurrent stacks require separate port mappings and matching application configuration.

## Troubleshooting

| Symptom                                         | Check                                                                                                                                |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Docker connection fails                         | Start Docker Desktop or Colima; inspect `./scripts/compose.sh ps`.                                                                   |
| Port 8081 is occupied                           | Stop the other ground-service runtime before starting this one.                                                                      |
| Browser says the ground service is reconnecting | Inspect `./scripts/compose.sh logs ground` or the local Java terminal.                                                               |
| Ground is live but spacecraft is disconnected   | Check simulator logs, contact windows, and whether **Disconnect 15s** is active.                                                     |
| Telemetry is stale                              | Restore contact and clear **Frozen telemetry** with **Nominal conditions** once the link is connected. Wait for a new measurement.   |
| Instrument is reserved                          | Resolve the active run in the console. Paused/aborting runs retain the reservation while their outcome is unresolved.                |
| Query returns `NOT_FOUND`                       | Check whether the runtime or simulator ledger changed. Preserve `UNKNOWN`; restarting or resending cannot prove the earlier outcome. |

The [AWS guide](../infra/aws/README.md) is an optional, separately configured deployment path. Local development requires no AWS resources.
