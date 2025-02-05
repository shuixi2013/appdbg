package jmp0.util

import com.googlecode.d2j.dex.Dex2jar
import jmp0.conf.CommonConf
import jmp0.decompiler.AppdbgDecompiler
import net.lingala.zip4j.model.ExcludeFileFilter
import org.apache.log4j.Logger
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.system.exitProcess

object DexUtils {
    val logger = Logger.getLogger(javaClass)

    private fun pathFilter(path:String):Boolean =
        //avoid JetBrains kotlin plugin inner error
         path.startsWith("org/intellij/lang/annotations") || path.startsWith("org/jetbrains/annotations")

    /**
     * @param byteArray 源dexFile
     * @param name 需要被释放成的文件夹
     * @param out 目的文件夹
     */
    fun releaseByteArrDexClassFile(byteArray: ByteArray,name: String,out: File,generateSourceLine:Boolean){
        // TODO: 2022/4/1 generate app class file with debug info,which make it possible to debug class file well
        Dex2jar.from(byteArray).eachClassData { data, className ->
            //first is the path where the class should be store
            //second is the name of class file
            className.split('/').let {
                Pair(it.subList(0,it.size-1).let { pathList->
                    StringBuilder().apply {
                        pathList.forEach { pathItem->
                            append("$pathItem${File.separator}")
                        }
                    }.toString()
                },it[it.size-1])
            }.also {
                if(pathFilter(it.first)) return@also
                try {
                    val f = File(File(out,it.first).apply {if (!exists()) mkdirs()},it.second+".class").apply { writeBytes(data) }
                    if (generateSourceLine){
                        AppdbgDecompiler(f).decompile()
                    }
                }catch (e:Exception){
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * @param dexFile 源dexFile
     * @param out 目的文件夹
     */
    fun releaseDexClassFile(dexFile: File,out: File,generateSourceLine:Boolean) =
        releaseByteArrDexClassFile(dexFile.readBytes(),dexFile.name,out,generateSourceLine)

    fun generateDevelopJar(out:File,generateJar:Boolean){
        //make jar debug libs for develop mode
        if (generateJar){
            ZipUtility.zip(out.path,File(File("${CommonConf.workDir}${File.separator}libs${File.separator}${CommonConf.appJarDir}").apply {
                if (!exists()) mkdirs()
                else {
                    deleteRecursively()
                    mkdirs()
                }},Random.nextULong().toString()+".jar").canonicalPath ){
                val temp = Path(out.path).relativize(Path(it.path))
                temp.startsWith("android") || temp.startsWith("kotlin")

            }
        }
    }

}