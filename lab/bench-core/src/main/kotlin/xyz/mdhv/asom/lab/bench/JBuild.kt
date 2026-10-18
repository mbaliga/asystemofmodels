package xyz.mdhv.asom.lab.bench

import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

/** Small builders for signed documents. Nothing here can make a value outside the integer profile. */
fun jo(vararg members: Pair<String, JValue>): JObject = JObject(members.toList())

fun jo(members: List<Pair<String, JValue>>): JObject = JObject(members)

fun ja(items: List<JValue>): JArray = JArray(items)

fun ja(vararg items: JValue): JArray = JArray(items.toList())

fun ji(v: Long): JValue = JInt(v)

fun ji(v: Int): JValue = JInt(v.toLong())

fun jiOrNull(v: Long?): JValue = if (v == null) JNull else JInt(v)

fun js(v: String): JValue = JString(v)

fun jsOrNull(v: String?): JValue = if (v == null) JNull else JString(v)

fun jb(v: Boolean): JValue = JBool(v)

fun jbOrNull(v: Boolean?): JValue = if (v == null) JNull else JBool(v)

fun jsList(v: List<String>): JValue = JArray(v.map { JString(it) })

fun jiList(v: List<Long>): JValue = JArray(v.map { JInt(it) })
