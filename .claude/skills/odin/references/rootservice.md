# RootService integration

Use RootService when privileged Android framework/Binder calls must run in a root process.
RootService extends `ContextWrapper`, not `android.app.Service`; use an explicit intent with
`RootService.bind()` / `unbind()`, not `Context.bindService()` / `startService()`. Odin 1.1 loads
the concrete class reflectively through the app's class loader and no-argument constructor.
It does not require a manifest `<service>` declaration. Do not export a component for root access.

Enable AIDL in the consuming Android module if it is not enabled:

```kotlin
android { buildFeatures { aidl = true } }
```

The `@Keep` example uses AndroidX annotations; add an explicit dependency if the app does not
already have one (for example, `implementation("androidx.annotation:annotation:1.9.1")`), or use
a targeted R8 keep rule for the concrete class and no-argument constructor instead.

For package `com.example.privileged`, add
`src/main/aidl/com/example/privileged/IRootIdentity.aidl`:

```aidl
package com.example.privileged;
interface IRootIdentity {
    int uid();
}
```

Add a concrete service with a no-argument constructor. Guard every privileged AIDL entry point
with `enforceCaller()` **inside the Binder transaction**, before privileged work. It accepts UID
0, UID 1000 or the app UID that started the process. Dependencies are in a separate process: do
not assume the UI process's DI singletons, coroutine scope or state.

```kotlin
package com.example.privileged

import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import com.valhalla.superuser.ipc.RootService

@Keep
class RootIdentityService : RootService() {
    override fun onBind(intent: Intent): IBinder = object : IRootIdentity.Stub() {
        override fun uid(): Int {
            enforceCaller()
            return Process.myUid()
        }
    }
}
```

`@Keep` preserves this app-owned reflective class/constructor under R8. Odin consumer rules keep
Odin's own package, not arbitrary app classes. Equivalent targeted rules are valid; verify the
minified consumer rather than adding broad package-wide keeps.

Use a lifecycle-owned `ServiceConnection`. Store `IRootIdentity.Stub.asInterface(binder)` on
connection, clear it on disconnect/binding death, and release the same connection on teardown:

```kotlin
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.valhalla.superuser.ipc.RootService

fun bindRootIdentity(context: Context, connection: ServiceConnection) {
    RootService.bind(Intent(context, RootIdentityService::class.java), connection)
}

fun unbindRootIdentity(connection: ServiceConnection) {
    RootService.unbind(connection)
}
```

Binding/unbinding must occur on main. The executor overload changes callback dispatch, not the
binding API's thread requirement. Potentially blocking remote methods belong on a worker. Handle
`RemoteException`/binder death and bound connection waiting at the app level: absence of root can
make bind a no-op without a failure callback.

`RootService.stop(intent)` stops the addressed service; Odin exits its root server when no active
services remain. `stopSelf()` stops that service inside the root process. Do not treat client
unbinding as permission to disrupt another owner's service. Add `CATEGORY_DAEMON_MODE` only when
continued lifetime across unbinds is intended. IPC needs its own cancellation protocol if required;
`JobHandle.cancel()` does not terminate a Binder operation. Validate app root, bind/unbind/rebind,
binder death, rejected callers and minified loading for the actual consumer design.
