package btm.m.auraplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import btm.m.auraplayer.ui.AuraRoot

class MainActivity : ComponentActivity() {
    private val model by viewModels<AuraViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AuraRoot(model) }
    }
}
