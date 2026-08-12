package app.zhijuan.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.zhijuan.core.contract.GenerationStarter
import app.zhijuan.reader.connection.ConnectionGatewayActions
import app.zhijuan.reader.creation.BookCreationActions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var connectionGateway: ConnectionGatewayActions

    @Inject
    lateinit var bookCreationActions: BookCreationActions

    @Inject
    lateinit var generationStarter: GenerationStarter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZhijuanApp(
                connectionGateway = connectionGateway,
                bookCreationActions = bookCreationActions,
                generationStarter = generationStarter,
            )
        }
    }
}
