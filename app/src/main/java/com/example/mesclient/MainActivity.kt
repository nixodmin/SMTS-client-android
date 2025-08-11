package com.example.smtsmessenger

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import java.math.BigInteger

class MainActivity : AppCompatActivity() {
    
    // UI компоненты
    private lateinit var clientInfoText: TextView
    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var sendFileButton: Button
    private lateinit var connectButton: Button
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    
    // Переменные для управления панелями
    private var isLeftPanelVisible = true
    private var lastClickTime: Long = 0
    
    // Адаптеры
    private lateinit var contactsAdapter: ContactsAdapter
    private lateinit var messagesAdapter: MessagesAdapter
    
    // Для выбора файлов
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    
    // Сетевые параметры
    private var serverHost = "127.0.0.1"
    private var serverPort = 5555
    private var handshakeSecret = "SECRET_HANDSHAKE_KEY"
    private var clientId: String? = null
    private var socket: Socket? = null
    
    // Криптография
    private val connections = ConcurrentHashMap<String, Connection>()
    private val dhPrivateKeys = ConcurrentHashMap<String, BigInteger>()
    private val dhPrime = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
        "29024E088A67CC74020BBEA63B139B22514A08798E3404D" +
        "DEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C" +
        "245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F40" +
        "6B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651EC" +
        "E45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
        "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529" +
        "077096966D670C354E4ABC9804F1746C08CA18217C32905" +
        "E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C" +
        "55DF06F4C52C9DE2BCBF6955817183995497CEA956AE51" +
        "5D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF", 16
    )
    private val dhBase = BigInteger.valueOf(2)
    
    // Coroutines
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Константы для разрешений
    companion object {
        private const val STORAGE_PERMISSION_REQUEST = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initFilePickerLauncher()
        checkStoragePermissions()
        initViews()
        setupAdapters()
        loadConfig()
    }
    
    private fun initFilePickerLauncher() {
        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    sendFileFromUri(uri)
                }
            }
        }
    }
    
    private fun checkStoragePermissions() {
        // Для Android 13+ нужны новые разрешения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            
            val missingPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), STORAGE_PERMISSION_REQUEST)
            }
        } else {
            // Для более старых версий Android
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            
            val missingPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            
            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), STORAGE_PERMISSION_REQUEST)
            }
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    addMessage("Разрешения на доступ к файлам получены")
                } else {
                    addMessage("[!] Для отправки и сохранения файлов нужны разрешения")
                }
            }
        }
    }
    
    private fun initViews() {
        clientInfoText = findViewById(R.id.clientInfoText)
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)
        sendButton = findViewById(R.id.sendButton)
        sendFileButton = findViewById(R.id.sendFileButton)
        connectButton = findViewById(R.id.connectButton)
        leftPanel = findViewById(R.id.leftPanel)
        rightPanel = findViewById(R.id.rightPanel)
        
        sendButton.setOnClickListener { sendMessage() }
        sendFileButton.setOnClickListener { sendFile() }
        connectButton.setOnClickListener { showConnectDialog() }
        
        // Настраиваем двойное нажатие на левую панель
        leftPanel.setOnClickListener { handlePanelClick() }
        
        // Настраиваем одинарное нажатие на правую панель для возврата
        rightPanel.setOnClickListener { handlePanelClick() }
    }
    
    private fun handlePanelClick() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < 300) { // Двойное нажатие (300ms)
            togglePanels()
        }
        lastClickTime = currentTime
    }
    
    private fun togglePanels() {
        if (isLeftPanelVisible) {
            // Скрываем левую панель, показываем правую
            leftPanel.visibility = View.GONE
            rightPanel.visibility = View.VISIBLE
            isLeftPanelVisible = false
            addMessage("Переключено на панель сообщений. Двойное нажатие - возврат к контактам.")
        } else {
            // Показываем левую панель, скрываем правую
            leftPanel.visibility = View.VISIBLE
            rightPanel.visibility = View.GONE
            isLeftPanelVisible = true
        }
    }
    
    private fun setupAdapters() {
        contactsAdapter = ContactsAdapter { contact ->
            // Выбор контакта для отправки сообщений
        }
        contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        contactsRecyclerView.adapter = contactsAdapter
        
        messagesAdapter = MessagesAdapter()
        messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesRecyclerView.adapter = messagesAdapter
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_switch_panel -> {
                togglePanels()
                true
            }
            R.id.menu_settings -> {
                showSettingsDialog()
                true
            }
            R.id.menu_connect -> {
                connectToServer()
                true
            }
            R.id.menu_disconnect -> {
                disconnect()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun loadConfig() {
        val prefs = getSharedPreferences("smts_config", Context.MODE_PRIVATE)
        serverHost = prefs.getString("server_host", serverHost) ?: serverHost
        serverPort = prefs.getInt("server_port", serverPort)
        handshakeSecret = prefs.getString("handshake_secret", handshakeSecret) ?: handshakeSecret
    }
    
    private fun saveConfig() {
        val prefs = getSharedPreferences("smts_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("server_host", serverHost)
            putInt("server_port", serverPort)
            putString("handshake_secret", handshakeSecret)
            apply()
        }
    }
    
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val hostEdit = view.findViewById<EditText>(R.id.hostEditText)
        val portEdit = view.findViewById<EditText>(R.id.portEditText)
        val secretEdit = view.findViewById<EditText>(R.id.secretEditText)
        
        hostEdit.setText(serverHost)
        portEdit.setText(serverPort.toString())
        secretEdit.setText(handshakeSecret)
        
        AlertDialog.Builder(this)
            .setTitle("Настройки подключения")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                try {
                    serverHost = hostEdit.text.toString()
                    serverPort = portEdit.text.toString().toInt()
                    handshakeSecret = secretEdit.text.toString()
                    saveConfig()
                    Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка сохранения настроек", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun showConnectDialog() {
        val input = EditText(this)
        input.hint = "ID клиента"
        
        AlertDialog.Builder(this)
            .setTitle("Подключение к клиенту")
            .setView(input)
            .setPositiveButton("Подключить") { _, _ ->
                val targetId = input.text.toString()
                if (targetId.isNotEmpty()) {
                    startDHExchange(targetId)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    
    private fun connectToServer() {
        scope.launch(Dispatchers.IO) {
            try {
                socket = Socket(serverHost, serverPort)
                
                // Отправляем handshake
                val handshakeMsg = JSONObject().apply {
                    put("secret", handshakeSecret)
                }
                sendJson(handshakeMsg)
                
                // Получаем welcome
                val welcome = receiveJson()
                if (welcome != null && welcome.has("client_id")) {
                    clientId = welcome.getString("client_id")
                    
                    withContext(Dispatchers.Main) {
                        clientInfoText.text = "ID: $clientId"
                        addMessage("Подключено как $clientId")
                    }
                    
                    // Запускаем получение сообщений
                    receiveMessages()
                } else {
                    throw Exception("Handshake failed")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка подключения: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                disconnect()
            }
        }
    }
    
    private fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Игнорируем ошибки при отключении
            }
            socket = null
            clientId = null
            connections.clear()
            dhPrivateKeys.clear()
            
            withContext(Dispatchers.Main) {
                clientInfoText.text = "Не подключено"
                contactsAdapter.updateContacts(emptyList())
                addMessage("Отключено от сервера")
            }
        }
    }
    
    private suspend fun receiveMessages() {
        while (socket?.isConnected == true) {
            try {
                val message = receiveJson()
                if (message != null) {
                    processMessage(message)
                } else {
                    break
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage("[!] Ошибка получения: ${e.message}")
                }
                break
            }
        }
    }
    
    private suspend fun processMessage(message: JSONObject) {
        withContext(Dispatchers.Main) {
            when (message.getString("type")) {
                "dh_init" -> {
                    val from = message.getString("from")
                    addMessage("\n[DH] Получен запрос обмена ключами от $from")
                    
                    try {
                        val prime = BigInteger(message.getString("prime"))
                        val base = BigInteger(message.getString("base"))
                        val theirPublicKey = BigInteger(message.getString("public_key"))
                        
                        val privateKey = generatePrivateKey(prime)
                        val publicKey = base.modPow(privateKey, prime)
                        
                        dhPrivateKeys[from] = privateKey
                        
                        // Отправляем ответ
                        scope.launch(Dispatchers.IO) {
                            val response = JSONObject().apply {
                                put("type", "dh_response")
                                put("from", clientId)
                                put("target_id", from)
                                put("public_key", publicKey.toString())
                            }
                            sendJson(response)
                        }
                        
                        // Вычисляем общий ключ
                        val sharedSecret = theirPublicKey.modPow(privateKey, prime)
                        val aesKey = deriveAESKey(sharedSecret, from, from)
                        val keyHash = aesKey.take(8).joinToString("") { "%02x".format(it) }
                        
                        connections[from] = Connection("established", aesKey, keyHash)
                        updateContactsList()
                        
                        addMessage("[DH] Обмен ключами с $from завершен")
                        addMessage("[DH] Отпечаток ключа: $keyHash")
                        
                    } catch (e: Exception) {
                        addMessage("[!] Ошибка обмена ключами: ${e.message}")
                    }
                }
                
                "dh_response" -> {
                    val from = message.getString("from")
                    addMessage("\n[DH] Получен ответ от $from")
                    completeDHExchange(from, message.getString("public_key"), true)
                }
                
                "message" -> {
                    val from = message.getString("from")
                    val decrypted = decryptMessage(message.getString("data"), from)
                    if (decrypted != null) {
                        addMessage("\n[Приватно от $from]: $decrypted")
                    }
                }
                
                "file" -> {
                    val from = message.getString("from")
                    val fileName = message.getString("file_name")
                    val fileData = decryptFileData(message.getString("data"), from)
                    if (fileData != null) {
                        saveFileToDownloads(fileName, fileData)
                        addMessage("\n[Файл от $from] Получен: $fileName")
                    }
                }
            }
        }
    }
    
    private fun startDHExchange(targetId: String) {
        if (socket == null) {
            addMessage("[!] Нет подключения к серверу")
            return
        }
        
        if (targetId == clientId) {
            addMessage("[!] Нельзя подключиться к себе")
            return
        }
        
        if (connections.containsKey(targetId)) {
            addMessage("[!] Подключение уже существует")
            return
        }
        
        connections[targetId] = Connection("pending", null, "")
        updateContactsList()
        
        scope.launch(Dispatchers.IO) {
            try {
                val privateKey = generatePrivateKey(dhPrime)
                val publicKey = dhBase.modPow(privateKey, dhPrime)
                
                dhPrivateKeys[targetId] = privateKey
                
                val message = JSONObject().apply {
                    put("type", "dh_init")
                    put("from", clientId)
                    put("target_id", targetId)
                    put("public_key", publicKey.toString())
                    put("prime", dhPrime.toString())
                    put("base", dhBase.toString())
                }
                
                sendJson(message)
                
                withContext(Dispatchers.Main) {
                    addMessage("[DH] Начало обмена ключами с $targetId")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage("[!] Ошибка инициации DH: ${e.message}")
                }
            }
        }
    }
    
    private fun completeDHExchange(targetId: String, theirPublicKeyStr: String, isInitiator: Boolean) {
        try {
            val theirPublicKey = BigInteger(theirPublicKeyStr)
            val privateKey = dhPrivateKeys[targetId] ?: return
            
            val sharedSecret = theirPublicKey.modPow(privateKey, dhPrime)
            val initiatorId = if (isInitiator) clientId else targetId
            val aesKey = deriveAESKey(sharedSecret, targetId, initiatorId!!)
            val keyHash = aesKey.take(8).joinToString("") { "%02x".format(it) }
            
            connections[targetId] = Connection("established", aesKey, keyHash)
            updateContactsList()
            
            addMessage("[DH] Обмен ключами с $targetId завершен!")
            addMessage("[DH] Отпечаток ключа: $keyHash")
            
        } catch (e: Exception) {
            addMessage("[!] Ошибка обмена ключами: ${e.message}")
        }
    }
    
    private fun generatePrivateKey(prime: BigInteger): BigInteger {
        val random = SecureRandom()
        val bits = prime.bitLength()
        var privateKey: BigInteger
        do {
            privateKey = BigInteger(bits, random)
        } while (privateKey >= prime || privateKey <= BigInteger.ONE)
        return privateKey
    }
    
    private fun deriveAESKey(sharedSecret: BigInteger, peerId: String, initiatorId: String): ByteArray {
        val clientIds = listOf(clientId!!, peerId).sorted()
        val keyMaterial = sharedSecret.toByteArray() + 
                         clientIds[0].toByteArray() + 
                         clientIds[1].toByteArray() + 
                         initiatorId.toByteArray()
        
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(keyMaterial)
    }
    
    private suspend fun sendJson(message: JSONObject) {
        val socket = this.socket ?: throw IOException("No connection")
        
        val messageStr = message.toString()
        val messageBytes = messageStr.toByteArray()
        val lengthBytes = ByteBuffer.allocate(4).putInt(messageBytes.size).array()
        
        socket.getOutputStream().write(lengthBytes)
        socket.getOutputStream().write(messageBytes)
        socket.getOutputStream().flush()
    }
    
    private suspend fun receiveJson(): JSONObject? {
        val socket = this.socket ?: return null
        
        // Читаем длину сообщения
        val lengthBytes = ByteArray(4)
        var totalRead = 0
        while (totalRead < 4) {
            val read = socket.getInputStream().read(lengthBytes, totalRead, 4 - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        
        val messageLength = ByteBuffer.wrap(lengthBytes).int
        
        // Читаем сообщение
        val messageBytes = ByteArray(messageLength)
        totalRead = 0
        while (totalRead < messageLength) {
            val read = socket.getInputStream().read(messageBytes, totalRead, messageLength - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        
        return JSONObject(String(messageBytes))
    }
    
    private fun encryptMessage(message: String, targetId: String): String? {
        val connection = connections[targetId] ?: return null
        if (connection.status != "established" || connection.key == null) return null
        
        try {
            val messageBytes = message.toByteArray()
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(connection.key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            
            val encrypted = cipher.doFinal(messageBytes)
            return Base64.encodeToString(iv + encrypted, Base64.DEFAULT)
        } catch (e: Exception) {
            addMessage("[!] Ошибка шифрования: ${e.message}")
            return null
        }
    }
    
    private fun decryptMessage(ciphertext: String, senderId: String): String? {
        val connection = connections[senderId] ?: return null
        if (connection.key == null) return null
        
        try {
            val data = Base64.decode(ciphertext, Base64.DEFAULT)
            if (data.size < 32) return null
            
            val iv = data.sliceArray(0..15)
            val encrypted = data.sliceArray(16 until data.size)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(connection.key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted)
        } catch (e: Exception) {
            addMessage("[!] Ошибка дешифрования: ${e.message}")
            return null
        }
    }
    
    private fun encryptFileData(fileData: ByteArray, targetId: String): String? {
        val connection = connections[targetId] ?: return null
        if (connection.status != "established" || connection.key == null) return null
        
        try {
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(connection.key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            
            val encrypted = cipher.doFinal(fileData)
            return Base64.encodeToString(iv + encrypted, Base64.DEFAULT)
        } catch (e: Exception) {
            addMessage("[!] Ошибка шифрования файла: ${e.message}")
            return null
        }
    }
    
    private fun decryptFileData(encryptedData: String, senderId: String): ByteArray? {
        val connection = connections[senderId] ?: return null
        if (connection.key == null) return null
        
        try {
            val data = Base64.decode(encryptedData, Base64.DEFAULT)
            if (data.size < 16) return null
            
            val iv = data.sliceArray(0..15)
            val encrypted = data.sliceArray(16 until data.size)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(connection.key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            return cipher.doFinal(encrypted)
        } catch (e: Exception) {
            addMessage("[!] Ошибка дешифрования файла: ${e.message}")
            return null
        }
    }
    
    private fun sendMessage() {
        val message = messageEditText.text.toString()
        if (message.isEmpty()) return
        
        val selectedContacts = contactsAdapter.getSelectedContacts()
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Выберите получателя", Toast.LENGTH_SHORT).show()
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                for (targetId in selectedContacts) {
                    val encrypted = encryptMessage(message, targetId) ?: continue
                    
                    val jsonMessage = JSONObject().apply {
                        put("type", "message")
                        put("from", clientId)
                        put("target_id", targetId)
                        put("data", encrypted)
                    }
                    
                    sendJson(jsonMessage)
                }
                
                withContext(Dispatchers.Main) {
                    addMessage("\n[Я → ${selectedContacts.joinToString(", ")}]: $message")
                    messageEditText.text.clear()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage("[!] Ошибка отправки: ${e.message}")
                }
            }
        }
    }
    
    private fun sendFile() {
        if (socket == null) {
            Toast.makeText(this, "Нет подключения к серверу", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedContacts = contactsAdapter.getSelectedContacts()
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Выберите получателя", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Создаем Intent для выбора файла
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*" // Разрешаем выбирать любые файлы
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        
        try {
            filePickerLauncher.launch(Intent.createChooser(intent, "Выберите файл для отправки"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка открытия файлового менеджера", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun sendFileFromUri(uri: Uri) {
        val selectedContacts = contactsAdapter.getSelectedContacts()
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Выберите получателя", Toast.LENGTH_SHORT).show()
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri) ?: "unknown_file"
                val fileData = readFileFromUri(uri)
                
                if (fileData == null) {
                    withContext(Dispatchers.Main) {
                        addMessage("[!] Не удалось прочитать файл")
                    }
                    return@launch
                }
                
                // Проверяем размер файла (ограничение 10МБ)
                if (fileData.size > 10 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        addMessage("[!] Файл слишком большой (максимум 10МБ)")
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    addMessage("[+] Отправка файла '$fileName' (${fileData.size} байт)...")
                }
                
                for (targetId in selectedContacts) {
                    val encrypted = encryptFileData(fileData, targetId) ?: continue
                    
                    val jsonMessage = JSONObject().apply {
                        put("type", "file")
                        put("from", clientId)
                        put("target_id", targetId)
                        put("file_name", fileName)
                        put("data", encrypted)
                    }
                    
                    sendJson(jsonMessage)
                }
                
                withContext(Dispatchers.Main) {
                    addMessage("[+] Файл '$fileName' отправлен: ${selectedContacts.joinToString(", ")}")
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage("[!] Ошибка отправки файла: ${e.message}")
                }
            }
        }
    }
    
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        result = it.getString(displayNameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
    
    private fun readFileFromUri(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun saveFileToDownloads(fileName: String, data: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для Android 10+ используем MediaStore API
                saveFileWithMediaStore(fileName, data)
            } else {
                // Для более старых версий используем прямой доступ
                saveFileDirectly(fileName, data)
            }
        } catch (e: Exception) {
            addMessage("[!] Ошибка сохранения файла: ${e.message}")
            // Fallback - сохраняем во внутреннее хранилище приложения
            try {
                val file = File(filesDir, fileName)
                file.writeBytes(data)
                addMessage("Файл сохранен во внутреннем хранилище: ${file.absolutePath}")
            } catch (e2: Exception) {
                addMessage("[!] Критическая ошибка сохранения: ${e2.message}")
            }
        }
    }
    
    private fun saveFileWithMediaStore(fileName: String, data: ByteArray) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }
            addMessage("Файл сохранен в Downloads: $fileName")
            addMessage("Путь: ${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName")
        } else {
            throw Exception("Не удалось создать файл в Downloads")
        }
    }
    
    private fun saveFileDirectly(fileName: String, data: ByteArray) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        
        // Создаем папку если её нет
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        
        // Создаем уникальное имя файла если файл уже существует
        var file = File(downloadsDir, fileName)
        var counter = 1
        val nameWithoutExt = fileName.substringBeforeLast(".", fileName)
        val extension = if (fileName.contains(".")) fileName.substringAfterLast(".") else ""
        
        while (file.exists()) {
            val newName = if (extension.isNotEmpty()) {
                "${nameWithoutExt}_${counter}.${extension}"
            } else {
                "${nameWithoutExt}_${counter}"
            }
            file = File(downloadsDir, newName)
            counter++
        }
        
        // Записываем файл
        file.writeBytes(data)
        addMessage("Файл сохранен в Downloads: ${file.name}")
        addMessage("Путь: ${file.absolutePath}")
    }
    
    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            else -> "application/octet-stream"
        }
    }
    
    private fun addMessage(message: String) {
        runOnUiThread {
            messagesAdapter.addMessage(Message(message, System.currentTimeMillis()))
            messagesRecyclerView.scrollToPosition(messagesAdapter.itemCount - 1)
        }
    }
    
    private fun updateContactsList() {
        runOnUiThread {
            val contactsList = connections.map { (id, conn) ->
                Contact(id, conn.status, conn.keyHash)
            }
            contactsAdapter.updateContacts(contactsList)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        disconnect()
    }
    
    // Вспомогательные классы
    data class Connection(
        val status: String,
        val key: ByteArray?,
        val keyHash: String
    )
    
    data class Contact(
        val id: String,
        val status: String,
        val keyHash: String,
        var isSelected: Boolean = false
    )
    
    data class Message(
        val text: String,
        val timestamp: Long
    )
}