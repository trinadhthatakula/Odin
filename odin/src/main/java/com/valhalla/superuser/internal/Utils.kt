package com.valhalla.superuser.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.util.ArraySet
import android.util.Log
import com.valhalla.superuser.Shell
import com.valhalla.superuser.internal.MainShell
import com.valhalla.superuser.internal.UiThreadHandler
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Objects

// StaticFieldLeak: `context` intentionally holds the process-lived application context — setContext()
// walks to applicationContext, getContext() falls back to ActivityThread.currentApplication(), and in
// the root server process it is that process's own package context. It never references an
// Activity/View, so this process-global singleton (for a root-shell library) cannot leak one.
// Suppressed at object scope because lint anchors the report on the object declaration.
@SuppressLint("StaticFieldLeak")
@Suppress("unused")
internal object Utils {
    private const val TAG = "LIBSU"
    private var synchronizedCollectionClass: Class<*>? = null
    private var currentRootState = -1

    @JvmField
    var context: Context? = null

    @JvmStatic
    fun log(log: Any) {
        log(TAG, log)
    }

    @JvmStatic
    fun log(tag: String, log: Any) {
        if (vLog()) {
            Log.d(tag, log.toString())
        }
    }

    @JvmStatic
    fun ex(t: Throwable) {
        if (vLog()) {
            Log.d(TAG, "", t)
        }
    }

    @JvmStatic
    fun err(t: Throwable) {
        err(TAG, t)
    }

    @JvmStatic
    fun err(tag: String, t: Throwable) {
        Log.d(tag, "", t)
    }

    @JvmStatic
    fun vLog(): Boolean {
        return Shell.enableVerboseLogging
    }

    @JvmStatic
    fun setContext(c: Context) {
        var tempContext = getContextImpl(c)
        val app = tempContext.applicationContext
        if (app != null) {
            tempContext = app
        }
        context = getContextImpl(tempContext)
    }

    @SuppressLint("PrivateApi")
    @JvmStatic
    fun getContext(): Context {
        if (context == null) {
            runCatching {
                val c = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as Context
                context = getContextImpl(c)
            }.onFailure { e ->
                err(e)
            }
        }
        return context!!
    }

    @JvmStatic
    fun getDeContext(): Context {
        // minSdk 24: the device-protected storage context is always available.
        return getContext().createDeviceProtectedStorageContext()
    }

    @JvmStatic
    fun getContextImpl(context: Context): Context {
        var ctx = context
        while (ctx is ContextWrapper) {
            ctx = ctx.baseContext
        }
        return ctx
    }

    @JvmStatic
    fun hasStartupAgents(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val agents = File(context.codeCacheDir, "startup_agents")
        return agents.isDirectory
    }

    @JvmStatic
    fun isSynchronized(collection: Collection<*>): Boolean {
        if (synchronizedCollectionClass == null) {
            synchronizedCollectionClass = Collections.synchronizedCollection(emptyList<Any>()).javaClass
        }
        return synchronizedCollectionClass!!.isInstance(collection)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun pump(`in`: InputStream, out: OutputStream): Long {
        var read: Int
        var total: Long = 0
        val buf = ByteArray(64 * 1024)
        while ((`in`.read(buf).also { read = it }) > 0) {
            out.write(buf, 0, read)
            total += read.toLong()
        }
        return total
    }

    @JvmStatic
    fun <E> newArraySet(): MutableSet<E> {
        // minSdk 24: ArraySet (added in API 23) is always available.
        return ArraySet()
    }

    @Synchronized
    @JvmStatic
    fun isAppGrantedRoot(): Boolean? {
        if (currentRootState < 0) {
            if (Process.myUid() == 0) {
                currentRootState = 2
                return true
            }
            val pathEnv = System.getenv("PATH") ?: ""
            for (path in pathEnv.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                val su = File(path, "su")
                if (su.canExecute()) {
                    currentRootState = 1
                    return null
                }
            }
            currentRootState = 0
            return false
        }
        return when (currentRootState) {
            0 -> false
            2 -> true
            else -> null
        }
    }

    @Synchronized
    @JvmStatic
    fun setConfirmedRootState(value: Boolean) {
        currentRootState = if (value) 2 else 0
    }

    @JvmStatic
    fun isRootImpossible(): Boolean {
        return Objects.equals(isAppGrantedRoot(), false)
    }

    @JvmStatic
    fun isMainShellRoot(): Boolean {
        return MainShell.get().isRoot
    }

    @JvmStatic
    fun isProcess64Bit(): Boolean {
        // minSdk 24 >= M: Process.is64Bit() is always available, so the old VMRuntime reflection
        // fallback (for API < 23) was dead code and has been removed along with its
        // DiscouragedPrivateApi suppression.
        return Process.is64Bit()
    }
}
