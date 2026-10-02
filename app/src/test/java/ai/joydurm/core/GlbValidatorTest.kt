package ai.joydurm.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbValidatorTest {
    private val nodes = """[{"name":"kick","mesh":0,"children":[8]},{"name":"snare","mesh":0},{"name":"tom1","mesh":0},{"name":"tom2","mesh":0},{"name":"floor","mesh":0},{"name":"hat","mesh":0},{"name":"crash","mesh":0},{"name":"ride","mesh":0},{"name":"beater","translation":[0,-0.26,0.32]}]"""
    private fun json(nodeJson: String=nodes, rootExtras: String="", viewExtras: String="", accessorCount: Int=3,
        imageJson: String="", bufferSize: Int=42): String = """{
        "asset":{"version":"2.0"},"buffers":[{"byteLength":$bufferSize}],
        "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}$viewExtras],
        "accessors":[{"bufferView":0,"componentType":5126,"count":$accessorCount,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
        "nodes":$nodeJson,"scenes":[{"nodes":[0,1,2,3,4,5,6,7]}],"scene":0$imageJson$rootExtras} """
    private fun geometry(): ByteArray = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN).apply {
        listOf(0f,0f,0f,1f,0f,0f,0f,1f,0f).forEach(::putFloat)
        putShort(0); putShort(1); putShort(2)
    }.array()
    private fun glb(json: String=json(), bin: ByteArray=geometry()): ByteArray {
        val j=json.toByteArray(Charsets.UTF_8); val jLength=(j.size+3)/4*4; val bLength=(bin.size+3)/4*4
        return ByteBuffer.allocate(28+jLength+bLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67); putInt(2); putInt(capacity()); putInt(jLength); putInt(0x4e4f534a)
            put(j); repeat(jLength-j.size) { put(32) }; putInt(bLength); putInt(0x004e4942); put(bin)
        }.array()
    }
    private fun rejected(bytes: ByteArray, reason: String?=null) {
        try { GlbValidator.validate(bytes); fail("Invalid GLB was accepted") }
        catch(error: IllegalArgumentException) { if(reason!=null) assertTrue(error.message.orEmpty(),error.message.orEmpty().contains(reason)) }
        catch(error: IllegalStateException) { if(reason!=null) assertTrue(error.message.orEmpty(),error.message.orEmpty().contains(reason)) }
    }
    @Test fun boundedEmbeddedModelPassesAndCountsInstances() {
        val summary=GlbValidator.validate(glb())
        assertEquals(9,summary.nodes); assertEquals(1,summary.meshes)
        assertEquals(24,summary.vertices); assertEquals(8,summary.triangles)
    }
    @Test fun preparedBytesRemainImmutableWhenOriginalChanges() {
        val original=glb(); val prepared=GlbValidator.prepare(original)
        original.fill(0)
        val data=prepared.buffer(); assertTrue(data.isReadOnly); assertTrue(data.isDirect)
        val checked=ByteArray(data.remaining()); data.get(checked)
        assertEquals(9,GlbValidator.validate(checked).nodes)
        assertEquals(64,prepared.sha256.length)
        assertEquals(checked.size,prepared.buffer().remaining())
    }
    @Test fun badHeadersLengthsAndExternalResourcesAreRejectedBeforeGpu() {
        rejected(glb().also { it[0]=0 }); rejected(glb().copyOf(40))
        rejected(glb(json().replace("\"byteLength\":42", "\"byteLength\":42,\"uri\":\"remote.bin\"")),"内嵌")
        rejected(glb(json().replace("\"byteLength\":36", "\"byteLength\":400")),"越界")
    }
    @Test fun missingDuplicateAndNestedDrumRootsAreRejected() {
        rejected(glb(json(nodes.replace("\"snare\"","\"kick\""))),"唯一 kick")
        rejected(glb(json(nodes.replace("\"snare\"","\"missing\""))),"唯一 snare")
        val nested=json(nodes.replace("\"children\":[8]", "\"children\":[1,8]")).replace("[0,1,2,3,4,5,6,7]","[0,2,3,4,5,6,7]")
        rejected(glb(nested),"互相嵌套")
    }
    @Test fun beaterAtSceneRootIsRejected() {
        val old=json(nodes.replace(",\"children\":[8]", "")).replace("[0,1,2,3,4,5,6,7]","[0,1,2,3,4,5,6,7,8]")
        rejected(glb(old),"kick 的子装配")
    }
    @Test fun nodeAndAccessorBudgetsCannotBeBypassedBySmallFiles() {
        val huge=nodes.dropLast(1)+","+(0..248).joinToString(",") { "{}" }+"]"
        rejected(glb(json(huge)),"节点数量")
        rejected(glb(json(accessorCount=250001)),"属性数量")
    }
    @Test fun invalidBinaryFloatsAndIndicesAreRejected() {
        rejected(glb(bin=geometry().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(0,Float.NaN) }),"非有限")
        rejected(glb(bin=geometry().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(40,3) }),"索引越界")
    }
    @Test fun floatBoundsCannotOverflowOrExceedPositionBudget() {
        rejected(glb(json().replace("\"max\":[1,1,0]", "\"max\":[1e300,1,0]")),"FLOAT min/max 溢出")
        rejected(glb(json().replace("\"min\":[0,0,0]", "\"min\":[-1e300,0,0]")),"FLOAT min/max 溢出")
        rejected(glb(json().replace("\"max\":[1,1,0]", "\"max\":[21,1,0]")),"20 米")
    }
    @Test fun reversedAndNonEnclosingPositionBoundsAreRejected() {
        rejected(glb(json().replace("\"min\":[0,0,0]", "\"min\":[2,0,0]")),"min 不能大于 max")
        rejected(glb(json().replace("\"max\":[1,1,0]", "\"max\":[0.5,1,0]")),"未包围真实顶点")
        rejected(glb(json().replace("\"min\":[0,0,0]", "\"min\":[0.5,0,0]")),"未包围真实顶点")
    }
    @Test fun compressionAndSparseAccessorsAreRejected() {
        rejected(glb(json(rootExtras=",\"extensionsUsed\":[\"KHR_draco_mesh_compression\"]")),"压缩")
        rejected(glb(json().replace("\"count\":3,\"type\":\"VEC3\"", "\"count\":3,\"type\":\"VEC3\",\"sparse\":{}")),"稀疏")
    }
    private fun png(width: Int,height: Int): ByteArray = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN).apply {
        putLong(-8552249625308161526L); putInt(13); putInt(0x49484452); putInt(width); putInt(height)
        put(8); put(6); put(0); put(0); put(0); putInt(0)
    }.array()
    @Test fun textureDimensionsAndDecodedBytesAreBudgetedWithoutDecoding() {
        fun textured(width: Int,height: Int, copies: Int=1): ByteArray {
            val bin=geometry()+byteArrayOf(0,0)+png(width,height)
            val image=",\"images\":[{\"bufferView\":2,\"mimeType\":\"image/png\"}],\"textures\":["+(0 until copies).joinToString(",") { "{\"source\":0}" }+"]"
            return glb(json(bufferSize=80,viewExtras=",{\"buffer\":0,\"byteOffset\":44,\"byteLength\":36}",imageJson=image),bin)
        }
        assertEquals(262144,GlbValidator.validate(textured(256,256)).decodedTextureBytes)
        rejected(textured(8192,1),"4096")
        rejected(textured(4096,4096),"32 MB")
        rejected(textured(2048,2048,2),"32 MB")
    }
    @Test fun malformedJsonAndSingularTransformsAreRejected() {
        rejected(glb(json().replace("\"scene\":0", "\"scene\":0,\"scene\":0")),"键重复")
        rejected(glb(json(nodes.replace("\"translation\":[0,-0.26,0.32]", "\"scale\":[0,1,1]"))),"零缩放")
        rejected(glb(json(rootExtras=",\"extras\":"+"[".repeat(34)+"0"+"]".repeat(34))),"结构超过预算")
    }
    @Test fun originalBundledKitPassesFullPreGpuValidation() {
        val local=File("src/main/assets/models/joydurm-kit.glb")
        val file=if(local.exists())local else File("app/src/main/assets/models/joydurm-kit.glb")
        val summary=GlbValidator.prepare(file).summary
        assertEquals(92,summary.meshes); assertTrue(summary.triangles>1000)
        assertEquals(0,summary.images)
    }
}
