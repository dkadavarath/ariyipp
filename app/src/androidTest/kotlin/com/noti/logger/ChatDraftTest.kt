package com.noti.logger

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noti.logger.ui.ChatActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A reply that couldn't be sent from the notification opens the chat with the text back in the box. */
@RunWith(AndroidJUnit4::class)
class ChatDraftTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun an_unsent_reply_comes_back_in_the_message_box() {
        val intent = Intent(ctx, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_SENDER, "+15550001111")
            .putExtra(ChatActivity.EXTRA_DRAFT, "on my way")
        ActivityScenario.launch<ChatActivity>(intent).use { scenario ->
            scenario.onActivity { a -> assertEquals("on my way", a.findViewById<EditText>(R.id.et_compose).text.toString()) }
            // Not put back again on a rotation: what's in the box by then is the user's.
            scenario.onActivity { a -> a.findViewById<EditText>(R.id.et_compose).setText("on my way, 5 min") }
            scenario.recreate()
            scenario.onActivity { a -> assertEquals("on my way, 5 min", a.findViewById<EditText>(R.id.et_compose).text.toString()) }
        }
    }
}
