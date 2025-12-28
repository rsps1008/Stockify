import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rsps1008.stockify.StockifyApplication
import com.rsps1008.stockify.ui.viewmodel.SettingsViewModel
import com.rsps1008.stockify.ui.viewmodel.ViewModelFactory

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val stockifyApplication = application as StockifyApplication

    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory(
            stockDao = stockifyApplication.database.stockDao(),
            settingsDataStore = stockifyApplication.settingsDataStore,
            application = application
        )
    )
    val refreshInterval by viewModel.refreshInterval.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onMessageShown()
        }
    }

    val refreshOptions = listOf(3, 5, 10, 30)

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "更新股票頻率", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            refreshOptions.forEach { interval ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RadioButton(
                        selected = refreshInterval == interval,
                        onClick = { viewModel.setRefreshInterval(interval) }
                    )
                    Text(text = "$interval 秒")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Box(contentAlignment = Alignment.Center) {
            Button(
                onClick = { viewModel.updateStockList() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text(text = "更新股票列表")
            }
            if (isLoading) {
                CircularProgressIndicator()
            }
        }


        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.deleteAllData() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "一鍵刪除所有持股與紀錄")
        }
    }
}