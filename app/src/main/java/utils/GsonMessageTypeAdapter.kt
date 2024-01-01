package utils

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.util.LinkedList

// https://www.javadoc.io/static/com.google.code.gson/gson/2.10.1/com.google.gson/com/google/gson/TypeAdapter.html
// https://www.kancloud.cn/apachecn/howtodoinjava-zh/1953303
class GsonMessageTypeAdapter : TypeAdapter<GsonMessage?>() {
    @Throws(IOException::class)
    private fun writeString(writer: JsonWriter, key: String?, value: String?) {
        writer.name(key).value(value)
    }

    @Throws(IOException::class)
    private fun writeLinkedList(writer: JsonWriter, key: String?, list: LinkedList<String>?) {
        writer.name(key).beginArray()
        if (list != null) {
            for (s in list) {
                writer.value(s)
            }
        }
        writer.endArray()
    }

    @Throws(IOException::class)
    override fun write(jsonWriter: JsonWriter, gsonMessage: GsonMessage?) {
        if (gsonMessage == null) {
            jsonWriter.nullValue()
            return
        }
        jsonWriter.beginObject()
        // ID
        writeString(jsonWriter, "id", gsonMessage.id)
        // DATA
        writeLinkedList(jsonWriter, "data", gsonMessage.data)
        // NOTES
        writeString(jsonWriter, "notes", gsonMessage.notes)
        jsonWriter.endObject()
        jsonWriter.close()
    }

    @Throws(IOException::class)
    private fun readAsLinkedList(reader: JsonReader): LinkedList<String> {
        val strings = LinkedList<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            strings.add(reader.nextString())
        }
        reader.endArray()
        return strings
    }

    @Throws(IOException::class)
    override fun read(jsonReader: JsonReader): GsonMessage? {
        var id: String? = ""
        var data = LinkedList<String>()
        var notes: String? = ""
        if (jsonReader.peek() == JsonToken.NULL) {
            jsonReader.nextNull()
            return null
        }
        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val name = jsonReader.nextName()
            if (name == "id") {
                id = jsonReader.nextString()
            } else if (name == "notes") {
                notes = jsonReader.nextString()
            } else if (name == "data" && jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
                data = readAsLinkedList(jsonReader)
            } else {
                jsonReader.skipValue()
            }
        }
        jsonReader.endObject()
        return GsonMessage(id!!, data, notes)
    }
}