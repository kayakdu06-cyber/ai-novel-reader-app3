package app.zhijuan.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.zhijuan.reader.connection.ConnectionWizardGateway
import app.zhijuan.reader.creation.BookCreationGateway
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var connectionWizardGateway: ConnectionWizardGateway

    @Inject
    lateinit var bookCreationGateway: BookCreationGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZhijuanApp(
                connectionGateway = connectionWizardGateway,
                bookCreationActions = bookCreationGateway,
            )
        }
    }
}
