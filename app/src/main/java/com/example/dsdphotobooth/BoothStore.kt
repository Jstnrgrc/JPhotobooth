package com.example.dsdphotobooth

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

internal data class Ticket(val id: Long, val code: String, val state: String, val batch: Long, val issued: Boolean)
internal data class Session(val id: Long, val ticket: Long, val photos: Int, val captured: Int, val state: String, val copies: Int, val layoutKey: String?)
internal data class Batch(val id: Long, val count: Int)
internal data class Job(val id: Long, val session: Long, val state: String)

internal class BoothStore(context: Context) : SQLiteOpenHelper(context, "photobooth.db", null, 9) {
    private val prefs = context.getSharedPreferences("booth_control", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    val welcomeHeadline: String get() = prefs.getString("welcome_headline",null)
        ?: "Welcome to Daily Sweet Doze Photobooth"
    fun setWelcomeHeadline(value: String) = prefs.edit().putString("welcome_headline",value).commit()
    val copyLimit get() = prefs.getInt("copy_limit",OrderPricing.MAX_COPIES).coerceIn(1,OrderPricing.MAX_COPIES)
    val fullscreenEvent get() = prefs.getBoolean("fullscreen_event",false)
    val extraCopyCents get() = prefs.getLong("extra_copy_cents",0L).takeIf { it > 0 }
    fun saveGeneralSettings(copies: Int, fullscreen: Boolean, extra: Long?) =
        prefs.edit().putInt("copy_limit",copies).putBoolean("fullscreen_event",fullscreen)
            .putLong("extra_copy_cents",extra ?: 0L).commit()

    private fun upgradeEventSettings(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sale_orders ADD COLUMN extra_cents INTEGER")
        db.execSQL("CREATE TABLE photo_transforms(session INTEGER NOT NULL REFERENCES sessions(id), slot INTEGER NOT NULL, flags INTEGER NOT NULL, PRIMARY KEY(session,slot))")
    }
    fun photoTransforms(id: Long) = readableDatabase.rawQuery("SELECT slot,flags FROM photo_transforms WHERE session=?",arrayOf(id.toString())).use {
        buildMap { while (it.moveToNext()) put(it.getInt(0),it.getInt(1)) }
    }
    fun transformPhoto(id: Long, slot: Int, option: Int) = transaction { db ->
        val current = sessions(db,"WHERE s.id=?",arrayOf(id.toString())).firstOrNull() ?: return@transaction
        if (current.state != "PREVIEW" || slot !in 1..current.photos || option !in 0..3) return@transaction
        val flags = if (option == 3) 0 else (photoTransforms(id)[slot] ?: 0) xor (1 shl option)
        db.execSQL("INSERT OR REPLACE INTO photo_transforms VALUES(?,?,?)",arrayOf<Any>(id,slot,flags))
    }
    val sessionMode get() = prefs.getBoolean("photobooth_session_mode",false)
    fun setSessionMode(enabled: Boolean) = prefs.edit().putBoolean("photobooth_session_mode",enabled).commit()
    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE batches(id INTEGER PRIMARY KEY, quantity INTEGER NOT NULL, photos INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tickets(id INTEGER PRIMARY KEY, lookup TEXT UNIQUE NOT NULL, sealed TEXT NOT NULL, batch INTEGER NOT NULL REFERENCES batches(id), state TEXT NOT NULL DEFAULT 'AVAILABLE', issued INTEGER, cash TEXT)")
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY, ticket INTEGER UNIQUE NOT NULL REFERENCES tickets(id), captured INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'CAPTURE', finished INTEGER, border TEXT NOT NULL DEFAULT 'Classic')")
        createRetakeTable(db)
        createOrderTables(db)
        createTicketPackages(db)
        createArtworkTables(db)
        upgradeDesignStorage(db)
        createCustomLayouts(db)
        upgradeEventSettings(db)
        db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY, session INTEGER UNIQUE NOT NULL REFERENCES sessions(id), state TEXT NOT NULL DEFAULT 'QUEUED')")
        db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY, action TEXT NOT NULL, ref INTEGER NOT NULL, at INTEGER NOT NULL)")
    }
    private fun createTicketPackages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ticket_packages(batch INTEGER PRIMARY KEY REFERENCES batches(id), copies INTEGER NOT NULL CHECK(copies BETWEEN 1 AND 5), unit_cents INTEGER NOT NULL CHECK(unit_cents > 0))")
    }

    private fun createOrderTables(db: SQLiteDatabase, legacy: Boolean = false) {
        val max = if (legacy) 3 else 4
        val layout = if (legacy) "" else ", layout_key TEXT"
        db.execSQL("CREATE TABLE layout_prices(photos INTEGER PRIMARY KEY CHECK(photos BETWEEN 1 AND $max), unit_cents INTEGER NOT NULL CHECK(unit_cents > 0))")
        db.execSQL("CREATE TABLE sale_orders(id INTEGER PRIMARY KEY AUTOINCREMENT, photos INTEGER NOT NULL CHECK(photos BETWEEN 1 AND $max), copies INTEGER NOT NULL CHECK(copies BETWEEN 1 AND 5), unit_cents INTEGER NOT NULL CHECK(unit_cents > 0), created INTEGER NOT NULL, ticket INTEGER UNIQUE REFERENCES tickets(id), paid_at INTEGER$layout, CHECK((ticket IS NULL) = (paid_at IS NULL)))")
    }

    private fun createCustomLayouts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE custom_layouts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ratio REAL NOT NULL, slots TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0)")
    }
    private fun customLayouts(where: String, args: Array<String>? = null) = readableDatabase.rawQuery(
        "SELECT id,name,ratio,slots FROM custom_layouts $where",args
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val slots = rows.getString(3).split(";").map { value ->
                    val (l,t,r,b) = value.split(",").map(String::toFloat)
                    PhotoSlot(l,t,r,b)
                }
                add(BoothLayout("custom-${rows.getLong(0)}",rows.getString(1),rows.getFloat(2),slots))
            }
        }
    }
    fun customLayouts() = customLayouts("WHERE archived=0 ORDER BY id")
    fun layout(key: String?): BoothLayout? {
        val id = key?.removePrefix("custom-")?.toLongOrNull()
        return if (key?.startsWith("custom-") == true && id != null)
            customLayouts("WHERE id=?",arrayOf(id.toString())).firstOrNull()
        else PhotoLayouts.named(key)
    }
    fun layoutForSession(key: String?, poses: Int) =
        layout(key)?.takeIf { it.poses == poses } ?: PhotoLayouts.forSession(key,poses)
    fun saveCustomLayout(name: String, ratio: Float, slots: List<PhotoSlot>, replaces: String?): BoothLayout = transaction { db ->
        require(name.isNotBlank() && ratio in 1.1f..3.8f && slots.size in 1..4)
        require(slots.indices.all { i -> slots[i].valid() && (0 until i).none { slots[i].overlaps(slots[it]) } })
        val id = db.insertOrThrow("custom_layouts",null,ContentValues().apply {
            put("name",name); put("ratio",ratio)
            put("slots",slots.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" })
        })
        if (replaces != null) check(archiveCustomLayout(db,replaces) == 1)
        BoothLayout("custom-$id",name,ratio,slots)
    }
    private fun archiveCustomLayout(db: SQLiteDatabase, key: String): Int {
        val id = key.takeIf { it.startsWith("custom-") }?.removePrefix("custom-")?.toLongOrNull() ?: return 0
        return db.update("custom_layouts",ContentValues().apply { put("archived",1) },
            "id=? AND archived=0",arrayOf(id.toString()))
    }
    fun archiveCustomLayout(key: String) = transaction { db -> archiveCustomLayout(db,key) == 1 }

    fun welcomeFraming() = CoverFraming(prefs.getFloat("welcome_zoom",1f),prefs.getFloat("welcome_x",0f),prefs.getFloat("welcome_y",0f))
    fun saveWelcomeFraming(value: CoverFraming) = prefs.edit().putFloat("welcome_zoom",value.zoom)
        .putFloat("welcome_x",value.x).putFloat("welcome_y",value.y).commit()

    private fun createArtworkTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE artwork_templates(id INTEGER PRIMARY KEY, layout_key TEXT NOT NULL, name TEXT NOT NULL, file_name TEXT NOT NULL UNIQUE, ratio REAL NOT NULL, slots TEXT NOT NULL)")
        db.execSQL("CREATE TABLE session_artwork(session INTEGER PRIMARY KEY REFERENCES sessions(id), template INTEGER NOT NULL REFERENCES artwork_templates(id))")
    }

    private fun upgradeDesignStorage(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE artwork_templates ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE design_time(session INTEGER PRIMARY KEY REFERENCES sessions(id), remaining INTEGER NOT NULL, deadline INTEGER)")
    }

    fun designDeadline(id: Long, now: Long): Long = transaction { db ->
        db.execSQL("INSERT OR IGNORE INTO design_time(session,remaining) VALUES(?,30000)",arrayOf(id))
        db.execSQL("UPDATE design_time SET deadline=?+remaining WHERE session=? AND deadline IS NULL",arrayOf(now,id))
        db.rawQuery("SELECT deadline FROM design_time WHERE session=?",arrayOf(id.toString())).use { it.moveToFirst(); it.getLong(0) }
    }
    fun pauseDesignTime(id: Long, now: Long) {
        writableDatabase.execSQL("UPDATE design_time SET remaining=MAX(0,MIN(30000,deadline-?)), deadline=NULL WHERE session=? AND deadline IS NOT NULL",arrayOf(now,id))
    }

    fun addArtwork(layout: BoothLayout, name: String, fileName: String, ratio: Float, slots: List<PhotoSlot>, replaces: Long? = null) =
        transaction { db ->
            require(name.isNotBlank() && layout.acceptsRatio(ratio) && slots.size == layout.poses)
            require(slots.indices.all { i -> slots[i].valid() && (!layout.centeredColumn || slots[i].isCentered()) && (0 until i).none { slots[i].overlaps(slots[it]) } })
            val id = db.insertOrThrow("artwork_templates",null,ContentValues().apply {
                put("layout_key",layout.key); put("name",name); put("file_name",fileName); put("ratio",ratio)
                put("slots",slots.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" })
            })
            if (replaces != null) check(archiveArtwork(db,layout.key,replaces) == 1)
            id
        }

    private fun archiveArtwork(db: SQLiteDatabase, layoutKey: String, id: Long) =
        db.update("artwork_templates",ContentValues().apply { put("archived",1) },
            "layout_key=? AND id=? AND archived=0",arrayOf(layoutKey,id.toString()))
    fun archiveArtwork(layout: BoothLayout, id: Long) = transaction { db -> archiveArtwork(db,layout.key,id) == 1 }

    private fun artwork(where: String, args: Array<String>) = readableDatabase.rawQuery(
        "SELECT id,layout_key,name,file_name,ratio,slots FROM artwork_templates $where",args
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val slots = rows.getString(5).split(";").map { encoded ->
                    val (l,t,r,b) = encoded.split(",").map(String::toFloat)
                    PhotoSlot(l,t,r,b)
                }
                add(ArtworkTemplate(rows.getLong(0),rows.getString(1),rows.getString(2),rows.getString(3),rows.getFloat(4),slots))
            }
        }
    }
    fun artworkFor(layout: String) = artwork("WHERE layout_key=? AND archived=0 ORDER BY id DESC",arrayOf(layout))
    fun selectedArtwork(session: Long) = artwork(
        "WHERE id=(SELECT template FROM session_artwork WHERE session=?)",arrayOf(session.toString())
    ).firstOrNull()
    fun selectArtwork(sessionId: Long, templateId: Long?) = transaction { db ->
        val session = sessions(db,"WHERE s.id=?",arrayOf(sessionId.toString())).firstOrNull() ?: return@transaction
        if (session.state != "PREVIEW") return@transaction
        if (templateId == null) db.delete("session_artwork","session=?",arrayOf(sessionId.toString()))
        else db.execSQL("INSERT OR REPLACE INTO session_artwork(session,template) SELECT ?,id FROM artwork_templates WHERE id=? AND layout_key=? AND archived=0",
            arrayOf<Any?>(sessionId,templateId,session.layoutKey))
    }

    private fun prices(db: SQLiteDatabase): Map<Int,Long> =
        db.rawQuery("SELECT photos,unit_cents FROM layout_prices",null).use {
            buildMap { while(it.moveToNext()) put(it.getInt(0),it.getLong(1)) }
        }
    fun prices() = prices(readableDatabase)
    fun savePrices(prices: Map<Int,Long>) = transaction { db ->
        require(prices.keys.containsAll(setOf(1,2,3)) && prices.keys.all { it in 1..4 } && prices.values.all { it in 1..OrderPricing.MAX_CENTS })
        if (4 !in prices) db.execSQL("DELETE FROM layout_prices WHERE photos=4")
        prices.forEach { (photos, cents) ->
            db.execSQL("INSERT OR REPLACE INTO layout_prices(photos,unit_cents) VALUES(?,?)",arrayOf<Any>(photos,cents))
        }
    }
    fun createOrder(layout: BoothLayout, copies: Int, quotedCents: Long, quotedExtra: Long?): SaleOrder? = transaction { db ->
        val cents = prices(db)[layout.poses] ?: return@transaction null
        if (cents != quotedCents || extraCopyCents != quotedExtra || copies !in 1..copyLimit) return@transaction null
        val order = SaleOrder(0,layout.poses,copies,cents,false,layout.key,quotedExtra,System.currentTimeMillis())
        val id = db.insertOrThrow("sale_orders",null,ContentValues().apply {
            put("photos", order.photos); put("copies", order.copies); put("unit_cents", order.unitCents); put("layout_key", layout.key)
            put("created", order.createdAt)
            put("extra_cents",quotedExtra)
        })
        order.copy(id = id)
    }
    private fun orders(db: SQLiteDatabase, where: String, args: Array<String>? = null) =
        db.rawQuery("SELECT id,photos,copies,unit_cents,ticket,layout_key,extra_cents,created FROM sale_orders $where", args).use {
            buildList { while(it.moveToNext()) add(SaleOrder(it.getLong(0),it.getInt(1),it.getInt(2),it.getLong(3),!it.isNull(4),it.getString(5),if(it.isNull(6)) null else it.getLong(6),it.getLong(7))) }
        }
    fun order(id: Long) = orders(readableDatabase,"WHERE id=?",arrayOf(id.toString())).firstOrNull()
    private fun createRetakeTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE retakes(session INTEGER NOT NULL REFERENCES sessions(id), ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 2), slot INTEGER NOT NULL CHECK(slot >= 1), PRIMARY KEY(session, ordinal))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN border TEXT NOT NULL DEFAULT 'Classic'")
        if (oldVersion < 3) createRetakeTable(db)
        if (oldVersion < 4) createOrderTables(db,legacy = true)
        if (oldVersion < 5) createTicketPackages(db)
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE layout_prices RENAME TO layout_prices_legacy")
            db.execSQL("ALTER TABLE sale_orders RENAME TO sale_orders_legacy")
            createOrderTables(db)
            db.execSQL("INSERT INTO layout_prices SELECT * FROM layout_prices_legacy")
            db.execSQL("INSERT INTO sale_orders(id,photos,copies,unit_cents,created,ticket,paid_at) SELECT id,photos,copies,unit_cents,created,ticket,paid_at FROM sale_orders_legacy")
            db.execSQL("DROP TABLE layout_prices_legacy")
            db.execSQL("DROP TABLE sale_orders_legacy")
            createArtworkTables(db)
        }
        if (oldVersion < 7) upgradeDesignStorage(db)
        if (oldVersion < 8) createCustomLayouts(db)
        if (oldVersion < 9) upgradeEventSettings(db)
    }
    fun retakes(id: Long): List<Pair<Int,Int>> =
        readableDatabase.rawQuery("SELECT slot,ordinal FROM retakes WHERE session=? ORDER BY ordinal", arrayOf(id.toString())).use {
            buildList { while (it.moveToNext()) add(it.getInt(0) to it.getInt(1)) }
        }
    fun recordRetake(id: Long, slot: Int, ordinal: Int): Boolean = transaction { db ->
        val session = sessions(db,"WHERE s.id=?",arrayOf(id.toString())).firstOrNull() ?: return@transaction false
        if (session.state != "PREVIEW" || slot !in 1..session.photos || ordinal !in 1..minOf(2, 5 - session.photos))
            return@transaction false
        val count = db.rawQuery("SELECT COUNT(*) FROM retakes WHERE session=?", arrayOf(id.toString())).use {
            it.moveToFirst(); it.getInt(0)
        }
        if (ordinal != count + 1) return@transaction false
        db.execSQL("INSERT INTO retakes(session,ordinal,slot) VALUES(?,?,?)",arrayOf<Any>(id,ordinal,slot))
        audit(db,"PHOTO_RETAKEN",id)
        true
    }
    fun border(session: Long): String =
        readableDatabase.rawQuery("SELECT border FROM sessions WHERE id=?", arrayOf(session.toString())).use {
            if (it.moveToFirst()) it.getString(0) else "Classic"
        }
    fun setBorder(session: Long, border: String) {
        require(border in listOf("Classic", "Bold", "Dashed"))
        writableDatabase.execSQL("UPDATE sessions SET border=? WHERE id=? AND state='PREVIEW'", arrayOf<Any>(border, session))
    }
    private inline fun <T> transaction(body: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try { body(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun audit(db: SQLiteDatabase, action: String, ref: Long) =
        db.execSQL("INSERT INTO audit(action,ref,at) VALUES(?,?,?)", arrayOf<Any>(action,ref,System.currentTimeMillis()))
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private fun key(alias: String, algorithm: String, purposes: Int): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, purposes)
        if (algorithm == "AES") spec.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        return KeyGenerator.getInstance(algorithm, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }
    private val lookupKey by lazy { key("booth-lookup-v1", "HmacSHA256", KeyProperties.PURPOSE_SIGN) }
    private val codeKey by lazy { key("booth-codes-v1", "AES", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) }
    private fun lookup(code: String) = encode(Mac.getInstance("HmacSHA256").apply { init(lookupKey) }.doFinal(code.toByteArray()))
    private fun seal(code: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, codeKey)
        return encode(cipher.iv) + ":" + encode(cipher.doFinal(code.toByteArray()))
    }
    private fun unseal(value: String): String {
        val parts = value.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, codeKey, GCMParameterSpec(128, decode(parts[0])))
        return String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
    }
    val hasAdmin get() = prefs.contains("admin_hash")
    private fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun createAdmin(password: String) {
        require(!hasAdmin && password.length >= 10)
        val salt = ByteArray(16).also(random::nextBytes)
        check(prefs.edit().putString("admin_salt", encode(salt)).putString("admin_hash", encode(hash(password, salt))).commit())
    }
    fun authenticate(password: String): Boolean {
        val salt = prefs.getString("admin_salt", null) ?: return false
        return MessageDigest.isEqual(hash(password, decode(salt)), decode(prefs.getString("admin_hash", "")!!))
    }
    fun cooldown(kind: String) = (prefs.getLong("${kind}_until", 0) - System.currentTimeMillis()).coerceAtLeast(0)
    fun attempt(kind: String, success: Boolean) {
        val now = System.currentTimeMillis()
        val recent = now - prefs.getLong("${kind}_last", 0) < 300_000
        val count = if (success) 0 else (if (recent) prefs.getInt("${kind}_count",0) else 0) + 1
        prefs.edit().putInt("${kind}_count",count).putLong("${kind}_last",now)
            .putLong("${kind}_until", if(count >= 5) now + (count - 4).coerceAtMost(5) * 60_000L else 0).commit()
    }
    private fun createBatch(db: SQLiteDatabase, quantity: Int, photos: Int, now: Long) =
        db.insertOrThrow("batches",null,ContentValues().apply {
            put("quantity",quantity); put("photos",photos); put("created",now)
        })

    private fun createTicket(db: SQLiteDatabase, batch: Long, issuedAt: Long? = null, cash: String? = null): Pair<Long,String> {
        repeat(250) {
            val code = random.nextInt(100_000).toString().padStart(5,'0')
            val id = db.insertWithOnConflict("tickets",null,ContentValues().apply {
                put("lookup",lookup(code)); put("sealed",seal(code)); put("batch",batch)
                if (issuedAt != null) put("issued",issuedAt)
                if (cash != null) put("cash",cash)
            },SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) return id to code
        }
        error("Could not allocate enough unique codes.")
    }
    fun generate(quantity: Int): Long = transaction { db ->
        require(quantity in 1..100)
        val used = db.rawQuery("SELECT COUNT(*) FROM tickets",null).use { it.moveToFirst(); it.getInt(0) }
        require(used + quantity <= 100_000) { "Ticket code space is exhausted." }
        val batch = createBatch(db,quantity,3,System.currentTimeMillis())
        repeat(quantity) { createTicket(db,batch) }
        audit(db,"BATCH_GENERATED",batch)
        batch
    }
    fun batches() = readableDatabase.rawQuery(
        "SELECT b.id,b.quantity FROM batches b WHERE NOT EXISTS (SELECT 1 FROM tickets t JOIN sale_orders o ON o.ticket=t.id JOIN audit a ON a.ref=o.id AND a.action='ORDER_PAID' WHERE t.batch=b.id) ORDER BY b.id DESC",null
    ).use {
        buildList { while(it.moveToNext()) add(Batch(it.getLong(0),it.getInt(1))) }
    }
    fun tickets(batch: Long) = readableDatabase.rawQuery(
        "SELECT id,sealed,state,batch,issued FROM tickets WHERE batch=? ORDER BY id",arrayOf(batch.toString())
    ).use {
        buildList { while(it.moveToNext()) add(Ticket(it.getLong(0),unseal(it.getString(1)),it.getString(2),it.getLong(3),!it.isNull(4))) }
    }
    fun void(ticket: Long) = transaction { db ->
        val count = db.update("tickets", ContentValues().apply { put("state","VOIDED") },
            "id=? AND state='AVAILABLE'",arrayOf(ticket.toString()))
        if(count == 1) audit(db,"VOIDED",ticket)
        count == 1
    }
    private val sessionColumns = "SELECT s.id,s.ticket,COALESCE(o.photos,b.photos),s.captured,s.state,COALESCE(o.copies,1),o.layout_key FROM sessions s JOIN tickets t ON t.id=s.ticket JOIN batches b ON b.id=t.batch LEFT JOIN sale_orders o ON o.ticket=t.id"
    private fun sessions(db: SQLiteDatabase, where: String, args: Array<String>? = null) =
        db.rawQuery("$sessionColumns $where",args).use {
            buildList { while(it.moveToNext()) add(Session(it.getLong(0),it.getLong(1),it.getInt(2),it.getInt(3),it.getString(4),it.getInt(5),it.getString(6))) }
        }
    fun photoSessions() = sessions(readableDatabase,"WHERE s.captured>=COALESCE(o.photos,b.photos) ORDER BY s.id DESC LIMIT 100")
    fun session(id: Long) = sessions(readableDatabase,"WHERE s.id=?",arrayOf(id.toString())).firstOrNull()
    private data class RedeemTicket(val id: Long,val state: String,val issued: Boolean)
    fun redeem(code: String, expectedOrder: Long? = null): Session? = transaction { db ->
        val row = db.rawQuery("SELECT id,state,issued FROM tickets WHERE lookup=?",arrayOf(lookup(code))).use {
            if(it.moveToFirst()) RedeemTicket(it.getLong(0),it.getString(1),!it.isNull(2)) else null
        } ?: return@transaction null
        val (id,state,issued) = row
        if (state !in listOf("AVAILABLE","RESERVED","REDEEMED")) return@transaction null
        if (expectedOrder != null) {
            val order = orders(db,"WHERE id=?",arrayOf(expectedOrder.toString())).firstOrNull() ?: return@transaction null
            if (order.paid) {
                val matches = db.rawQuery("SELECT 1 FROM sale_orders WHERE id=? AND ticket=?", arrayOf(expectedOrder.toString(),id.toString())).use { it.moveToFirst() }
                if (!matches) return@transaction null
            } else {
                if (state != "AVAILABLE") return@transaction null
                val now = System.currentTimeMillis()
                val attached = db.update("sale_orders",ContentValues().apply { put("ticket",id); put("paid_at",now) },
                    "id=? AND ticket IS NULL",arrayOf(expectedOrder.toString()))
                check(attached == 1)
                // An unused ticket is handed over after the attendant collects the posted price.
                if (!issued) db.execSQL("UPDATE tickets SET issued=?,cash=? WHERE id=? AND issued IS NULL",
                    arrayOf<Any>(now,OrderPricing.peso(order.totalCents),id))
                audit(db,"PHOTO_CODE_ACCEPTED",expectedOrder)
            }
        } else if (state == "AVAILABLE") {
            // A fresh code needs a selection and total. Preserve orders already paid under the previous flow.
            val previouslyPaid = db.rawQuery("SELECT 1 FROM sale_orders WHERE ticket=?",arrayOf(id.toString())).use { it.moveToFirst() }
            if (!previouslyPaid) return@transaction null
        }
        if(state == "AVAILABLE") {
            db.execSQL("UPDATE tickets SET state='RESERVED' WHERE id=?",arrayOf(id))
            db.execSQL("INSERT INTO sessions(ticket) VALUES(?)",arrayOf(id))
            audit(db,"SESSION_RESERVED",id)
        }
        sessions(db,"WHERE s.ticket=? AND s.state NOT IN ('COMPLETE','ENDED')",arrayOf(id.toString())).firstOrNull()
    }
    fun recordPhoto(id: Long, index: Int) = transaction { db ->
        val session = sessions(db,"WHERE s.id=?",arrayOf(id.toString())).firstOrNull() ?: return@transaction
        if(session.state != "CAPTURE" || index != session.captured + 1 || index > session.photos) return@transaction
        db.execSQL("UPDATE sessions SET captured=?, state=? WHERE id=?",arrayOf<Any>(index,if(index == session.photos) "PREVIEW" else "CAPTURE",id))
        db.execSQL("UPDATE tickets SET state='REDEEMED' WHERE id=? AND state='RESERVED'",arrayOf(session.ticket))
    }
    fun recoverPrints() {
        writableDatabase.execSQL("UPDATE jobs SET state='UNKNOWN' WHERE state='SENDING'")
    }
    fun printState(id: Long): String? =
        readableDatabase.rawQuery("SELECT state FROM jobs WHERE session=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    fun beginPrint(id: Long, reprint: Boolean) = transaction { db ->
        val session = sessions(db,"WHERE s.id=?",arrayOf(id.toString())).firstOrNull() ?: return@transaction false
        if (session.state !in listOf("PREVIEW","QUEUED","COMPLETE","ENDED") || (!reprint && session.state !in listOf("PREVIEW","QUEUED")))
            return@transaction false
        db.execSQL("INSERT OR IGNORE INTO jobs(session) VALUES(?)",arrayOf(id))
        val clause = if(reprint) "session=? AND state!='SENDING'" else "session=? AND state IN ('QUEUED','FAILED')"
        val changed = db.update("jobs",ContentValues().apply { put("state","SENDING") },clause,arrayOf(id.toString()))
        if (changed == 1) {
            db.execSQL("UPDATE sessions SET state='QUEUED' WHERE id=? AND state='PREVIEW'",arrayOf(id))
            audit(db,if(reprint) "REPRINT_REQUESTED" else "PRINT_STARTED",id)
        }
        changed == 1
    }
    fun finishPrint(id: Long, result: String) = transaction { db ->
        require(result in listOf("TRANSMITTED","FAILED","UNKNOWN"))
        val n = db.update("jobs",ContentValues().apply { put("state",result) },"session=? AND state='SENDING'",arrayOf(id.toString()))
        if (n == 1) {
            if (result == "TRANSMITTED") db.execSQL("UPDATE sessions SET state='COMPLETE', finished=COALESCE(finished,?) WHERE id=?",
                arrayOf(System.currentTimeMillis(),id))
            audit(db,"PRINT_" + result,id)
        }
    }
    fun endWithoutPrint(id: Long) = transaction { db ->
        val state = db.rawQuery("SELECT state FROM jobs WHERE session=?",arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (state !in listOf(null,"QUEUED","FAILED","UNKNOWN")) return@transaction false
        val now = System.currentTimeMillis()
        val updated = db.update("sessions",ContentValues().apply { put("state","ENDED"); put("finished",now) },
            "id=? AND state IN ('PREVIEW','QUEUED')",arrayOf(id.toString()))
        if (updated != 1) return@transaction false
        // Keep uncertain sends visible to the operator. A receipt might already be at the printer.
        if (state == null) db.execSQL("INSERT INTO jobs(session,state) VALUES(?,'NOT_PRINTED')",arrayOf(id))
        else if (state != "UNKNOWN") db.execSQL("UPDATE jobs SET state='NOT_PRINTED' WHERE session=?",arrayOf(id))
        audit(db,"SESSION_ENDED_WITHOUT_PRINT",id)
        true
    }
    fun jobs() = readableDatabase.rawQuery("SELECT id,session,state FROM jobs ORDER BY id DESC LIMIT 100",null).use {
        buildList { while(it.moveToNext()) add(Job(it.getLong(0),it.getLong(1),it.getString(2))) }
    }
    fun confirm(id: Long) = transaction { db ->
        val n = db.update("jobs",ContentValues().apply { put("state","CONFIRMED") },"session=? AND state NOT IN ('SENDING','CONFIRMED')",arrayOf(id.toString()))
        if(n == 1) {
            db.execSQL("UPDATE sessions SET state='COMPLETE', finished=COALESCE(finished,?) WHERE id=?",arrayOf(System.currentTimeMillis(),id))
            audit(db,"PRINT_CONFIRMED_BY_ATTENDANT",id)
        }
    }
    fun expiredPhotos() = readableDatabase.rawQuery("SELECT id FROM sessions WHERE finished<=?",arrayOf((System.currentTimeMillis()-86_400_000).toString())).use {
        buildList { while(it.moveToNext()) add(it.getLong(0)) }
    }
    fun report(): String = readableDatabase.rawQuery("SELECT COUNT(*), COUNT(issued), SUM(state='REDEEMED'), SUM(state='VOIDED') FROM tickets",null).use {
        it.moveToFirst()
        "Generated: ${it.getInt(0)}\nIssued: ${it.getInt(1)}\nRedeemed: ${it.getInt(2)}\nVoided: ${it.getInt(3)}"
    }
    fun cashReport() = readableDatabase.rawQuery("SELECT cash, COUNT(*) FROM tickets WHERE issued IS NOT NULL GROUP BY cash ORDER BY cash",null).use {
        buildList { while(it.moveToNext()) add("${it.getString(0)} × ${it.getInt(1)} ticket(s)") }.joinToString("\n")
    }
    fun exported(action: String, id: Long) = audit(writableDatabase,action,id)
}
