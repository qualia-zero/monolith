package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface

/**
 * JNI bridge matching libplayer.so / libmpv.so native symbols.
 * Package and class name MUST stay `is.xyz.mpv.MPVLib` to match the prebuilt JNI symbols.
 */
object MPVLib {
    init {
        System.loadLibrary("mpv")
        System.loadLibrary("player")
    }

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()
    external fun attachSurface(surface: Surface)
    external fun detachSurface()

    external fun command(cmd: Array<String>)
    external fun setOptionString(name: String, value: String): Int

    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)

    external fun observeProperty(property: String, format: Int)
    external fun grabThumbnail(dimension: Int): Bitmap?

    private val observers = ArrayList<EventObserver>()
    private val logObservers = ArrayList<LogObserver>()

    fun addObserver(o: EventObserver) {
        synchronized(observers) { observers.add(o) }
    }

    fun removeObserver(o: EventObserver) {
        synchronized(observers) { observers.remove(o) }
    }

    fun addLogObserver(o: LogObserver) {
        synchronized(logObservers) { logObservers.add(o) }
    }

    fun removeLogObserver(o: LogObserver) {
        synchronized(logObservers) { logObservers.remove(o) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        synchronized(observers) {
            for (o in observers) o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        synchronized(observers) {
            for (o in observers) o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        synchronized(observers) {
            for (o in observers) o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        synchronized(observers) {
            for (o in observers) o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        synchronized(observers) {
            for (o in observers) o.eventProperty(property)
        }
    }

    @JvmStatic
    fun event(eventId: Int) {
        synchronized(observers) {
            for (o in observers) o.event(eventId)
        }
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(logObservers) {
            for (o in logObservers) o.logMessage(prefix, level, text)
        }
    }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
    }

    object MpvEvent {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY = 4
        const val MPV_EVENT_COMMAND_REPLY = 5
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_PLAYBACK_RESTART = 18
        const val MPV_EVENT_SEEK = 21
    }
}
