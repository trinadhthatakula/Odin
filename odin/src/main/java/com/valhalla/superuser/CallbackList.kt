package com.valhalla.superuser

import com.valhalla.superuser.internal.UiThreadHandler
import java.util.AbstractList
import java.util.concurrent.Executor

/**
 * An [AbstractList] that calls `onAddElement` when a new element is added to the list.
 *
 *
 * To simplify the API of [Shell], both STDOUT and STDERR will output to [List]s.
 * This class is useful if you want to trigger a callback every time [Shell]
 * outputs a new line.
 *
 *
 * The `CallbackList` itself does not have a data store. If you need one, you can provide a
 * base [List], and this class will delegate its calls to it.
 */
public abstract class CallbackList<E>
/**
 * [.onAddElement] runs with the executor; no backing list.
 *
 * Internal: the executor-taking entry point is module-private so it does not leak the
 * internal `UiThreadHandler` default into the public/protected surface. In-module callers
 * (e.g. `Shell.Job.asFlow`) use it directly; external subclassers use the protected
 * no-executor constructor below.
 */ internal constructor(
    protected var mExecutor: Executor = UiThreadHandler.executor,
    protected var mBase: MutableList<E?>? = null
) : AbstractList<E?>() {
    /**
     * Subclass entry point: [.onAddElement] runs on the main thread; optional backing list.
     */
    protected constructor(base: MutableList<E?>? = null) : this(UiThreadHandler.executor, base)

    /**
     * The callback when a new element is added.
     *
     *
     * This method will be called after `add` is called.
     * Which thread it runs on depends on which constructor is used to construct the instance.
     * @param e the new element added to the list.
     */
    public abstract fun onAddElement(e: E?)

    /**
     * @see List.get
     */
    override fun get(i: Int): E? {
        return if (mBase == null) null else mBase!![i]
    }

    override fun set(i: Int, s: E?): E? {
        return if (mBase == null) null else mBase!!.set(i, s)
    }

    override fun add(i: Int, s: E?) {
        if (mBase != null) mBase!!.add(i, s)
        mExecutor.execute(Runnable { onAddElement(s) })
    }

    override fun remove(o: E?): Boolean {
        return if (mBase == null) false else mBase!!.remove(o)
    }

    override fun removeAt(index: Int): E? {
        return if (mBase == null) null else mBase!!.removeAt(index)
    }

    override fun removeFirst(): E? {
        return if (mBase == null || mBase!!.isEmpty()) null else mBase!!.removeAt(0)
    }

    override var size: Int
        get() = if (mBase == null) 0 else mBase!!.size
        set(value) {
            if (mBase == null) mBase = ArrayList(value)
            else mBase!!.clear()
            for (i in 0 until value) {
                mBase!!.add(null)
            }
        }


}
