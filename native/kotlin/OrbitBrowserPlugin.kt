package __PACKAGE__

import android.content.Intent
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "OrbitBrowser")
class OrbitBrowserPlugin : Plugin() {
    @PluginMethod
    fun open(call: PluginCall) {
        try {
            val intent = Intent(context, OrbitBrowserActivity::class.java)
            activity.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Unable to open Orbit Browser: ${e.message}")
        }
    }
}
