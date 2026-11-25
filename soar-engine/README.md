# AndroNG Soar adapter

Independent Android executable and SOCKS5 adapter for Lantern Soar v0.2.3.
The `upstream/` directory is the original Apache-2.0 source from
https://github.com/getlantern/soar/tree/v0.2.3.
Upstream archive SHA-256:
`db7d25db7ed21d5e6bf9eb921b465cb43a390e34ffc78bf7b02a0477ec9692c7`.
Keep `upstream/LICENSE` and the packaged `assets/licenses/soar-LICENSE.txt`.

The adapter accepts one JSON configuration on stdin and emits JSON status lines
on stdout. It checks manually selected resolvers sequentially using authenticated
Soar streams and a certificate-validated HTTPS 204 probe. Failed candidates are
closed before the next attempt. Discovery is an optional final attempt. The whole
startup is bounded by the profile connection timeout. After success it provides
SOCKS5 TCP on loopback. UDP association handles DNS only, translating it to DoH
over the Soar tunnel. Arbitrary UDP traffic is not supported.

On Android the service excludes its own UID from its VPN to prevent recursive
resolver routing. All other apps use the tunnel. The service runs in `:soar`,
separate from Xray's daemon so their native HEV instances cannot collide.
This engine currently does not support Android always-on restart.

Build with Go 1.27.2 and Android NDK r29 (API 28):

```powershell
./build.ps1 -Go /path/to/go.exe -Ndk /path/to/android-ndk-r29
```

The binaries are generated in `app/libs/<abi>/libandrong-soar.so`; Android extracts
them as executable PIE files through the existing legacy native packaging.
Do not upload a configuration containing user/server information to build it.
