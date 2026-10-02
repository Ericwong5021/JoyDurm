package ai.joydurm.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.abs

data class GlbSummary(val nodes: Int, val meshes: Int, val vertices: Long,
    val triangles: Long, val images: Int, val decodedTextureBytes: Long)

/** Owns an immutable copy of the bytes checked on an import worker, never an unchecked file path. */
class PreparedGlb internal constructor(private val data: ByteBuffer,
    val sha256: String, val summary: GlbSummary) {
    fun buffer(): ByteBuffer = data.asReadOnlyBuffer().apply { rewind() }
}

/** Bounded, embedded, static glTF 2.0 profile. Run prepare on a worker before any GPU allocation. */
object GlbValidator {
    const val MAX_FILE_BYTES = 20_000_000
    const val MAX_NODES = 256
    const val MAX_VERTICES = 250_000L
    const val MAX_TRIANGLES = 200_000L
    const val MAX_TEXTURE_BYTES = 32_000_000L
    private val drumNames = listOf("kick", "snare", "tom1", "tom2", "floor", "hat", "crash", "ride")
    private val safeExtensions = setOf("KHR_materials_unlit", "KHR_texture_transform")

    fun prepare(file: File): PreparedGlb {
        require(file.length() in 28..MAX_FILE_BYTES.toLong()) { "GLB 必须为 28 B–20 MB" }
        // Limit the read even if the file changes while it is being copied.
        val bytes = file.inputStream().use { it.readBytesBounded(MAX_FILE_BYTES) }
        return prepare(bytes)
    }

    fun prepare(bytes: ByteArray): PreparedGlb {
        require(bytes.size in 28..MAX_FILE_BYTES) { "GLB 必须为 28 B–20 MB" }
        val checked=bytes.copyOf()
        val summary = validate(checked)
        val immutable = ByteBuffer.allocateDirect(checked.size).apply { put(checked); flip() }.asReadOnlyBuffer()
        val hash = MessageDigest.getInstance("SHA-256").digest(checked).joinToString("") { "%02x".format(it) }
        return PreparedGlb(immutable, hash, summary)
    }

    fun validate(bytes: ByteArray): GlbSummary {
        require(bytes.size in 28..MAX_FILE_BYTES) { "GLB 必须为 28 B–20 MB" }
        val binary = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(binary.getInt(0) == 0x46546c67 && binary.getInt(4) == 2 &&
            binary.getInt(8) == bytes.size) { "GLB 文件头无效" }
        val jsonLength = binary.getInt(12)
        require(jsonLength in 4..2_000_000 && jsonLength % 4 == 0 &&
            20L + jsonLength + 8 <= bytes.size && binary.getInt(16) == 0x4e4f534a) { "GLB JSON 块无效" }
        val binHeader = 20 + jsonLength
        val binLength = binary.getInt(binHeader)
        require(binLength >= 0 && binLength % 4 == 0 && binary.getInt(binHeader + 4) == 0x004e4942 &&
            binHeader.toLong() + 8 + binLength == bytes.size.toLong()) { "GLB 必须包含唯一内嵌 BIN 块" }
        val binStart = binHeader + 8
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val json = decoder.decode(ByteBuffer.wrap(bytes, 20, jsonLength)).toString()
        val root = JsonReader(json).read().obj()
        require(root["asset"].obj()["version"] == "2.0") { "仅支持 glTF 2.0" }
        for (key in listOf("extensionsUsed", "extensionsRequired")) {
            require(root.array(key).all { it is String && it in safeExtensions }) { "不支持压缩或未验证的 GLB 扩展" }
        }
        require(root.array("skins").isEmpty() && root.array("animations").isEmpty()) { "鼓组须使用静态独立节点，不能包含蒙皮或预置动画" }
        val buffers = root.objects("buffers")
        require(buffers.size == 1 && buffers[0]["uri"] == null) { "模型及贴图必须内嵌 GLB" }
        val bufferSize = buffers[0].integer("byteLength")
        require(bufferSize > 0 && binLength - bufferSize in 0..3) { "GLB 缓冲区大小无效" }
        val views = root.objects("bufferViews")
        require(views.size in 1..512) { "GLB 缓冲区数量超过预算" }
        views.forEach { v ->
            require(v.integer("buffer", 0) == 0 && v["extensions"] == null) { "不支持外部或压缩几何" }
            val offset = v.integer("byteOffset", 0); val length = v.integer("byteLength")
            require(length > 0 && offset.toLong() + length <= bufferSize) { "GLB 缓冲区越界" }
        }
        val accessors = root.objects("accessors")
        require(accessors.size in 1..512) { "GLB 顶点属性数量超过预算" }
        data class Accessor(val start: Int, val count: Int, val width: Int, val components: Int,
            val stride: Int, val componentType: Int, val type: String,
            val minimum: List<Double>?, val maximum: List<Double>?)
        var accessorBytes = 0L
        val decoded = accessors.map { a ->
            require(a["sparse"] == null && a["extensions"] == null) { "不支持稀疏或压缩顶点属性" }
            val view = views.at(a.integer("bufferView"))
            val count = a.integer("count")
            require(count in 1..MAX_VERTICES.toInt()) { "GLB 顶点属性数量超过预算" }
            val componentType = a.integer("componentType")
            val width = when (componentType) { 5120,5121 -> 1; 5122,5123 -> 2; 5125,5126 -> 4; else -> error("GLB 顶点单位无效") }
            val type = a["type"] as? String ?: error("GLB 顶点类型缺失")
            val components = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("不支持矩阵顶点属性") }
            val element = width * components
            val stride = view.integer("byteStride", element)
            require(stride >= element && (view["byteStride"] == null || stride in 4..252 && stride % 4 == 0)) { "GLB 顶点步幅无效" }
            val offset = a.integer("byteOffset", 0)
            require((view.integer("byteOffset",0)+offset) % width == 0 && offset.toLong() + (count - 1L)*stride + element <= view.integer("byteLength")) { "GLB 顶点数据越界" }
            accessorBytes += count.toLong()*element
            require(accessorBytes <= 32_000_000) { "GLB 解码顶点数据超过预算" }
            val minimum=a["min"]?.numbers(components); val maximum=a["max"]?.numbers(components)
            if(componentType==5126) for(bound in listOfNotNull(minimum,maximum))
                require(bound.all { abs(it)<=Float.MAX_VALUE.toDouble() }) { "GLB FLOAT min/max 溢出" }
            if(minimum!=null && maximum!=null)
                require(minimum.indices.all { minimum[it]<=maximum[it] }) { "GLB min 不能大于 max" }
            Accessor(binStart + view.integer("byteOffset", 0) + offset, count, width, components, stride, componentType, type,minimum,maximum)
        }
        // Reject NaN/Infinity in binary float attributes, before the renderer sees them.
        decoded.filter { it.componentType == 5126 }.forEach { a ->
            repeat(a.count) { i -> repeat(a.components) { c ->
                require(binary.getFloat(a.start + i*a.stride + c*4).isFinite()) { "GLB 顶点包含非有限数值" }
            } }
        }
        val materials = root.objects("materials")
        val images = root.objects("images")
        val textures = root.objects("textures")
        require(materials.size <= 64 && images.size <= 16 && textures.size <= 16 && root.array("samplers").size <= 16) { "GLB 材质或贴图数量超过预算" }
        var textureBytes = 0L
        val imageBytes=mutableListOf<Long>()
        images.forEach { image ->
            require(image["uri"] == null && image["extensions"] == null) { "贴图须内嵌且为 PNG/JPEG" }
            val view = views.at(image.integer("bufferView"))
            val start = binStart + view.integer("byteOffset", 0)
            val length = view.integer("byteLength")
            val size = imageSize(bytes, start, length, image["mimeType"] as? String)
            require(size.first in 1..4096 && size.second in 1..4096) { "贴图边长不能超过 4096" }
            val decodedBytes=size.first.toLong()*size.second*4
            imageBytes += decodedBytes; textureBytes += decodedBytes
            require(textureBytes <= MAX_TEXTURE_BYTES) { "GLB RGBA 贴图解码超过 32 MB 预算" }
        }
        val imageUses=IntArray(images.size)
        textures.forEach { texture ->
            require(texture["extensions"] == null) { "不支持压缩贴图" }
            val source=texture.integer("source"); images.at(source)
            if(imageUses[source]++>0) textureBytes+=imageBytes[source]
            require(textureBytes<=MAX_TEXTURE_BYTES) { "GLB 贴图实例解码超过 32 MB 预算" }
            if(texture["sampler"] != null) root.array("samplers").at(texture.integer("sampler"))
        }
        fun checkMaterial(value: Any?) {
            when (value) {
                is Map<*,*> -> value.forEach { (k,v) ->
                    if(k == "extensions") require(v.obj().keys.all { it in safeExtensions }) { "不支持未验证的材质扩展" }
                    if(k is String && k.endsWith("Texture")) textures.at(v.obj().integer("index"))
                    checkMaterial(v)
                }
                is List<*> -> value.forEach(::checkMaterial)
            }
        }
        materials.forEach(::checkMaterial)
        val meshes = root.objects("meshes")
        require(meshes.size in 1..128) { "GLB 网格数量超过预算" }
        var primitives = 0
        var declaredVertices=0L; var declaredTriangles=0L
        val meshSizes = meshes.map { mesh ->
            var vertices = 0L; var triangles = 0L
            val parts = mesh.objects("primitives")
            require(parts.isNotEmpty()) { "GLB 网格为空" }
            parts.forEach { p ->
                primitives++
                require(primitives <= 256 && p.integer("mode", 4) == 4 && p["targets"] == null && p["extensions"] == null) { "仅支持有预算的静态三角网格" }
                val attributes = p["attributes"].obj()
                val positions = decoded.at(attributes["POSITION"].index())
                require(positions.type == "VEC3" && positions.componentType == 5126) { "位置须为 FLOAT VEC3" }
                val minimum=positions.minimum ?: error("POSITION 必须声明 min/max")
                val maximum=positions.maximum ?: error("POSITION 必须声明 min/max")
                require((minimum+maximum).all { abs(it)<=20 }) { "POSITION min/max 不能超过 20 米" }
                val indices = p["indices"]?.let { decoded.at(it.index()) }
                val indexCount = indices?.count ?: positions.count
                require(indexCount % 3 == 0) { "三角网格索引数量无效" }
                declaredVertices+=positions.count; declaredTriangles+=indexCount/3
                require(declaredVertices<=MAX_VERTICES && declaredTriangles<=MAX_TRIANGLES) { "GLB 声明几何超过预算" }
                repeat(positions.count) { i -> repeat(3) { c ->
                    val value=binary.getFloat(positions.start+i*positions.stride+c*4)
                    require(abs(value)<=20f) { "GLB 顶点位置不能超过 20 米" }
                    // Compare in the same FLOAT representation used by the native renderer.
                    require(value>=minimum[c].toFloat() && value<=maximum[c].toFloat()) { "POSITION min/max 未包围真实顶点" }
                } }
                require(attributes.keys.all { it in setOf("POSITION","NORMAL","TANGENT","TEXCOORD_0","TEXCOORD_1","COLOR_0") }) { "不支持该顶点属性" }
                attributes.values.forEach { require(decoded.at(it.index()).count == positions.count) { "顶点属性数量不一致" } }
                indices?.let { a ->
                    require(a.type == "SCALAR" && a.componentType in setOf(5121,5123,5125)) { "三角索引类型无效" }
                    repeat(a.count) { i ->
                        val at = a.start + i*a.stride
                        val index = when(a.width) { 1 -> bytes[at].toLong() and 255; 2 -> binary.getShort(at).toLong() and 65535; else -> binary.getInt(at).toLong() and 0xffffffffL }
                        require(index < positions.count) { "GLB 三角索引越界" }
                    }
                }
                if(p["material"] != null) materials.at(p.integer("material"))
                vertices += positions.count; triangles += indexCount/3
            }
            vertices to triangles
        }
        val nodes = root.objects("nodes")
        require(nodes.size in 1..MAX_NODES) { "GLB 节点数量超过预算" }
        val parents = IntArray(nodes.size) { -1 }
        val children = nodes.mapIndexed { parent,n ->
            require(n["skin"] == null && n["extensions"] == null) { "不支持蒙皮或特殊节点" }
            for ((key,count) in listOf("translation" to 3,"rotation" to 4,"scale" to 3,"matrix" to 16)) {
                if(n[key] != null) {
                    val values = n[key].numbers(count)
                    require(values.all { abs(it) <= 100 }) { "GLB 节点变换过大" }
                    if(key == "scale") require(values.all { abs(it) >= 0.001 }) { "GLB 节点不能使用零缩放" }
                    if(key == "rotation") require(abs(values.sumOf { it*it }-1)<0.01) { "GLB 旋转四元数未归一化" }
                    if(key == "matrix") {
                        require(listOf(3,7,11).all { abs(values[it])<1e-6 } && abs(values[15]-1)<1e-6) { "GLB 矩阵不是仿射变换" }
                        val determinant=values[0]*(values[5]*values[10]-values[9]*values[6])-values[4]*(values[1]*values[10]-values[9]*values[2])+values[8]*(values[1]*values[6]-values[5]*values[2])
                        require(abs(determinant)>1e-9) { "GLB 矩阵变换奇异" }
                    }
                }
            }
            require(n["matrix"] == null || listOf("translation","rotation","scale").all { n[it] == null }) { "GLB 变换不能同时使用矩阵与 TRS" }
            n.array("children").map { child ->
                val index = child.index(); nodes.at(index)
                require(parents[index] == -1 && index != parent) { "GLB 节点多父或循环" }
                parents[index] = parent; index
            }
        }
        val visited = BooleanArray(nodes.size)
        fun visit(index: Int, depth: Int) {
            require(depth <= 32 && !visited[index]) { "GLB 层级循环或过深" }
            visited[index] = true; children[index].forEach { visit(it, depth + 1) }
        }
        val scenes = root.objects("scenes")
        val scene = scenes.at(root.integer("scene", 0))
        scene.array("nodes").forEach { value ->
            val index = value.index(); nodes.at(index)
            require(parents[index] == -1) { "GLB 场景根节点具有父节点" }
            visit(index, 0)
        }
        require(visited.all { it }) { "GLB 含场景外或循环节点" }
        val drumRoots = drumNames.map { name ->
            val matches = nodes.indices.filter { (nodes[it]["name"] as? String)?.equals(name, true) == true }
            require(matches.size == 1) { "GLB 缺少唯一 $name 节点" }; matches.single()
        }.toSet()
        drumRoots.forEach { index ->
            var parent = parents[index]
            while(parent != -1) { require(parent !in drumRoots) { "GLB 鼓件不能互相嵌套" }; parent = parents[parent] }
        }
        val beaters = nodes.indices.filter { (nodes[it]["name"] as? String)?.equals("beater", true) == true }
        require(beaters.size <= 1) { "GLB 鼓槌节点重复" }
        beaters.singleOrNull()?.let { index ->
            val kick = nodes.indexOfFirst { (it["name"] as? String)?.equals("kick", true) == true }
            var parent = parents[index]
            while(parent != -1 && parent != kick) parent = parents[parent]
            require(parent == kick) { "beater 必须是 kick 的子装配" }
        }
        var vertices = 0L; var triangles = 0L
        nodes.forEach { n -> n["mesh"]?.let {
            val size = meshSizes.at(it.index()); vertices += size.first; triangles += size.second
            require(vertices <= MAX_VERTICES && triangles <= MAX_TRIANGLES) { "GLB 实例几何超过 250000 顶点或 200000 三角预算" }
        } }
        return GlbSummary(nodes.size, meshes.size, vertices, triangles, images.size, textureBytes)
    }

    private fun imageSize(bytes: ByteArray, start: Int, length: Int, mime: String?): Pair<Int,Int> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if(mime == "image/png") {
            require(length >= 33 && b.getLong(start) == -8552249625308161526L &&
                b.getInt(start+8) == 13 && b.getInt(start+12) == 0x49484452) { "PNG 文件头无效" }
            return b.getInt(start+16) to b.getInt(start+20)
        }
        require(mime == "image/jpeg" && length >= 4 && b.getShort(start).toInt() and 65535 == 0xffd8) { "仅支持内嵌 PNG/JPEG" }
        var p = start + 2; val end = start + length
        while(p + 4 <= end) {
            require(bytes[p++].toInt() and 255 == 255) { "JPEG 标记无效" }
            while(p < end && bytes[p].toInt() and 255 == 255) p++
            require(p < end) { "JPEG 文件截断" }
            val marker = bytes[p++].toInt() and 255
            require(marker != 0xd9 && marker != 0xda && p + 2 <= end) { "JPEG 缺少尺寸" }
            val size = b.getShort(p).toInt() and 65535
            require(size >= 2 && p.toLong()+size <= end) { "JPEG 段越界" }
            if(marker in setOf(0xc0,0xc1,0xc2)) {
                require(size >= 8) { "JPEG 尺寸段无效" }
                return (b.getShort(p+5).toInt() and 65535) to (b.getShort(p+3).toInt() and 65535)
            }
            p += size
        }
        error("JPEG 缺少尺寸")
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = read(buffer); if(n < 0) break; require(out.size().toLong()+n <= limit) { "GLB 超过 20 MB" }; out.write(buffer,0,n) }
        return out.toByteArray()
    }
    @Suppress("UNCHECKED_CAST") private fun Any?.obj(): Map<String,Any?> = this as? Map<String,Any?> ?: error("GLB JSON 对象无效")
    private fun Map<String,Any?>.array(key: String): List<Any?> = if(this[key] == null) emptyList() else this[key] as? List<Any?> ?: error("GLB JSON 数组无效")
    private fun Map<String,Any?>.objects(key: String) = array(key).map { it.obj() }
    private fun Any?.index(): Int {
        val n = this as? Double ?: error("GLB 索引不是整数")
        require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n == n.toInt().toDouble()) { "GLB 索引无效" }
        return n.toInt()
    }
    private fun Map<String,Any?>.integer(key: String, default: Int? = null) = this[key]?.index() ?: default ?: error("GLB 缺少 $key")
    private fun Any?.numbers(count: Int): List<Double> {
        val array = this as? List<*> ?: error("GLB 变换无效")
        require(array.size == count) { "GLB 数值维度无效" }
        return array.map { val n = it as? Double ?: error("GLB 数值无效"); require(n.isFinite()); n }
    }
    private fun <T> List<T>.at(index: Int): T { require(index in indices) { "GLB 引用越界" }; return this[index] }

    /** Small strict parser with depth/token caps; no Android JSON implementation in JVM tests. */
    private class JsonReader(private val text: String) {
        private var p = 0; private var tokens = 0
        fun read(): Any? { val value = value(0); space(); require(p == text.length) { "GLB JSON 尾部无效" }; return value }
        private fun space() { while(p < text.length && text[p] in " \n\r\t") p++ }
        private fun value(depth: Int): Any? {
            require(depth <= 32 && ++tokens <= 50_000) { "GLB JSON 结构超过预算" }; space(); require(p < text.length)
            return when(text[p]) {
                '{' -> { p++; space(); val result = linkedMapOf<String,Any?>()
                    if(take('}')) result else {
                        do { space(); require(p < text.length && text[p] == '"'); val key = string(); require(!result.containsKey(key)) { "GLB JSON 键重复" }; space(); require(take(':')); result[key] = value(depth+1); space() } while(take(','))
                        require(take('}')); result
                    }
                }
                '[' -> { p++; space(); val result = mutableListOf<Any?>()
                    if(take(']')) result else { do { result += value(depth+1); space() } while(take(',')); require(take(']')); result }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> { val begin = p; if(take('-')) require(p < text.length)
                    if(take('0')) { require(p == text.length || text[p] !in '0'..'9') } else { require(p < text.length && text[p] in '1'..'9'); while(p < text.length && text[p] in '0'..'9') p++ }
                    if(take('.')) { val first = p; while(p < text.length && text[p] in '0'..'9') p++; require(p > first) }
                    if(p < text.length && text[p] in "eE") { p++; if(p < text.length && text[p] in "+-") p++; val first = p; while(p < text.length && text[p] in '0'..'9') p++; require(p > first) }
                    val n = text.substring(begin,p).toDouble(); require(n.isFinite()) { "GLB JSON 非有限数值" }; n
                }
            }
        }
        private fun take(c: Char): Boolean = if(p < text.length && text[p] == c) { p++; true } else false
        private fun literal(token: String, value: Any?): Any? { require(text.startsWith(token,p)); p += token.length; return value }
        private fun string(): String {
            require(take('"')); val out = StringBuilder()
            while(p < text.length) { val c = text[p++]; if(c == '"') return out.toString(); require(c >= ' ')
                if(c != '\\') out.append(c) else { require(p < text.length); when(val escape = text[p++]) {
                    '"','\\','/' -> out.append(escape); 'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> { require(p+4 <= text.length); out.append(text.substring(p,p+4).toInt(16).toChar()); p += 4 }
                    else -> error("GLB JSON 转义无效")
                } }
            }
            error("GLB JSON 字符串截断")
        }
    }
}
