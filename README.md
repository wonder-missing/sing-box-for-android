# WonderSing (SFA fork)

Personal fork of [sing-box for Android](https://github.com/SagerNet/sing-box-for-android),
maintained on clawdy-mini. The app is published as `WonderSing` with the
`io.nekohasekai.sfa.wonder` application ID.

- Releases: https://github.com/wonder-missing/sing-box-for-android/releases
  (only `arm64-v8a` APKs are built)
- Agent rules, work loop and release flow: [AGENTS.md](AGENTS.md)
- Conductor control-plane vision and phased plan: [docs/agent-control-plane.md](docs/agent-control-plane.md)
- Local signing notes and limitations: [docs/signing.md](docs/signing.md)

## Fork notes

- In-app update checks read this fork's GitHub Releases without a GitHub API token.
- Telemetry is optional and configured from Settings → Telemetry.
- Maintained by agents on clawdy-mini; open work lives in [TODO.md](TODO.md).

## Upstream

Experimental Android client for sing-box, the universal proxy platform.

### Documentation

https://sing-box.sagernet.org/installation/clients/sfa/

## License

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Under the license, that forks of the app are not allowed to be listed on F-Droid or other app stores
under the original name.
