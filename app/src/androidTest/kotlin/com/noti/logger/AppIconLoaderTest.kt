package com.noti.logger

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.util.AppIconLoader
import com.noti.logger.util.AppInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the lazy icon path against a package that is genuinely installed on the device
 * (our own), since the picker resolves icons per row rather than up front.
 */
@RunWith(AndroidJUnit4::class)
class AppIconLoaderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun self(component: ComponentName? = ComponentName(ctx.packageName, "com.noti.logger.ui.MainActivity")) =
        AppInfo(ctx.packageName, "noti", component)

    @Test
    fun loads_the_icon_for_an_installed_component() = runBlocking {
        val icon = AppIconLoader(ctx).load(self())
        assertNotNull("expected a drawable for our own launcher component", icon)
    }

    @Test
    fun falls_back_to_the_application_icon_when_there_is_no_component() = runBlocking {
        val icon = AppIconLoader(ctx).load(self(component = null))
        assertNotNull("expected the application icon as a fallback", icon)
    }

    @Test
    fun caches_the_icon_after_the_first_load() = runBlocking {
        val loader = AppIconLoader(ctx)
        assertNull("nothing should be cached before the first load", loader.cached(ctx.packageName))

        val first = loader.load(self())
        assertSame("cached() must return the loaded drawable", first, loader.cached(ctx.packageName))
        assertSame("a second load must reuse the cached drawable", first, loader.load(self()))
    }

    @Test
    fun unknown_package_yields_null_rather_than_throwing() = runBlocking {
        val missing = AppInfo("com.does.not.exist.anywhere", "Nope", null)
        assertNull(AppIconLoader(ctx).load(missing))
    }

    @Test
    fun unresolvable_component_yields_null_rather_than_throwing() = runBlocking {
        val bogus = AppInfo(
            ctx.packageName,
            "noti",
            ComponentName(ctx.packageName, "com.noti.logger.ui.NoSuchActivity")
        )
        assertNull(AppIconLoader(ctx).load(bogus))
    }
}
