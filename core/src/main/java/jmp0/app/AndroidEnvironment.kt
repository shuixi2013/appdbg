package jmp0.app

import javassist.*
import jmp0.apk.ApkFile
import jmp0.app.classloader.ClassLoadedCallbackBase
import jmp0.app.classloader.FrameWorkClassNoFoundException
import jmp0.app.classloader.XAndroidClassLoader
import jmp0.app.conversation.AppdbgConversationSchemaEnum
import jmp0.app.conversation.IAppdbgConversationHandler
import jmp0.app.interceptor.intf.IInterceptor
import jmp0.app.interceptor.intf.NativeImplementInterceptor
import jmp0.app.interceptor.intf.RuntimeClassInterceptorBase
import jmp0.app.interceptor.unidbg.UnidbgInterceptor
import jmp0.app.mock.annotations.ClassReplaceTo
import jmp0.app.mock.MethodManager
import jmp0.conf.CommonConf
import jmp0.util.SystemReflectUtils
import jmp0.util.ZipUtility
import org.apache.log4j.Logger
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI
import java.util.*

// TODO: 2022/3/9 模拟初始化Android activity，并载入自定义类加载器
class AndroidEnvironment(val apkFile: ApkFile,
                         private val methodInterceptor: IInterceptor,
                         private val androidRuntimeClass: ClassLoadedCallbackBase = object :
                             ClassLoadedCallbackBase(){
                             override fun beforeResolveClassImpl(
                                 androidEnvironment: AndroidEnvironment,
                                 className: String,
                                 classLoader: XAndroidClassLoader
                             ): Class<*>? {
                                 return null
                             }
                         }) {
    private val logger = Logger.getLogger(javaClass)
    private val androidLoader = XAndroidClassLoader(this)
    private val conversationHandlerMap:EnumMap<AppdbgConversationSchemaEnum,IAppdbgConversationHandler> = EnumMap<AppdbgConversationSchemaEnum,IAppdbgConversationHandler>(AppdbgConversationSchemaEnum::class.java)
    val id = UUID.randomUUID().toString()
    var processName = id
    var context:Any

    init {
        //create temp dir
        File(CommonConf.workDir,CommonConf.tempDirName).apply { if (!exists()) mkdir() }
        registerToContext()
        checkAndReleaseFramework()
        loadUserSystemClass()
        //impotent init MethodManager and set java method hook
        MethodManager.getInstance(id).getMethodMap().forEach{
            registerMethodHook(it.key,true)
        }
        initConversationHandler()
        initIOResolver()
        val loopClazz = findClass("android.os.Looper")
        loopClazz.getDeclaredMethod("prepareMainLooper").invoke(null)
        context = findClass("jmp0.app.mock.system.user.UserContext").getDeclaredConstructor().newInstance()
    }

    private fun initConversationHandler(){
        if (methodInterceptor is NativeImplementInterceptor)
            setConversationHandler(AppdbgConversationSchemaEnum.NATIVE, methodInterceptor)
    }

    private fun initIOResolver(){
        try {
            val clazz = Class.forName("java.io.PathInterceptorManager")
            val iPathInterceptorClazz = Class.forName("java.io.IPathInterceptor")
            val method = clazz.getDeclaredMethod("getInstance")
            val ins = method.invoke(null);
            val field = clazz.getDeclaredField("nameInterceptor")
            // FIXME: 2022/5/28 *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message transform method call failed at JPLISAgent.c line: 844
            field.set(ins, Proxy.newProxyInstance(null, arrayOf(iPathInterceptorClazz),object: InvocationHandler {
                override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>): Any? {
                    if(method.name == "pathFilter"){
                        if (args[0] == null) return null
                        when(args[0]){
                            is String->{
                                val ret =try { methodInterceptor.ioResolver(args[0] as String) }
                                catch (e:Exception){ IInterceptor.ImplStatus(false,null) }
                                return if (!ret.implemented) args[0]
                                else ret.result.toString()
                            }
                            is URI ->{
                                val ret = methodInterceptor.ioResolver((args[0] as URI).path)
                                return if (!ret.implemented) ret
                                else URI(ret.result.toString())
                            }
                        }
                    }
                    throw Exception("unknown exception...")
                }

            }))
        }catch (e:Exception){
            logger.warn("io Resolver init failed, io redirect unusable!")
            logger.warn("check rt.jar had been replaced!")
        }
    }

    /**
     * if you don't want to use any more,
     * please invoke this method
     */
    fun destroy(){
        DbgContext.unRegister(id)
    }

    fun getMethodInterceptor() = methodInterceptor

    fun setProcessName(name: String) = apply { processName = name }

    private fun registerToContext(){
        DbgContext.register(uuid = id,this)
    }

    /**
     * must bypass jdk security check
     * must bypass jdk security check
     * must bypass jdk security check
     *  ${jdkPath}/Home/jre/lib/server/libjvm.dylib
     *  characteristic string => Prohibited package name:
     *  modify java/ to xxxxx before characteristic string
     */
    private fun loadUserSystemClass(){
        SystemReflectUtils.getAllClassWithAnnotation(CommonConf.Mock.userSystemClassPackageName, ClassReplaceTo::class.java){ fullClassName,isInner->
            val ctClass = ClassPool.getDefault().getCtClass(fullClassName)
            if (!isInner){
                val targetClassName = (ctClass.annotations.find { annotation-> annotation is ClassReplaceTo } as ClassReplaceTo).to
                if (targetClassName != "") ctClass.replaceClassName(fullClassName,targetClassName)
            }
            //set uuid as xxUuid
            try {
                val field = ctClass.getDeclaredField("xxUuid")
                ctClass.removeField(field)
                ctClass.addField(CtField.make("public static String xxUuid = \"$id\";",ctClass))
            }catch (e: NotFoundException){
                ctClass.addField(CtField.make("public static String xxUuid = \"$id\";",ctClass))
            }
            //bugfix compile file maybe fail
            var ba: ByteArray
            while (true){
                try {
                    ba = ctClass.toBytecode()
                    break
                }catch (e: CannotCompileException){
                    val className = e.cause!!.message!!
                    loadClass(className)
                }
            }

            androidLoader.xDefineClass(null,ba,0,ba.size)
            ctClass.defrost()
        }

    }

    fun getClassLoader() = androidLoader

    private fun checkAndReleaseFramework(){
        val frameworkDir = File("${CommonConf.workDir}${File.separator}${CommonConf.tempDirName}${File.separator}${CommonConf.frameworkDirName}")
        if(!frameworkDir.exists()){
            frameworkDir.mkdir()
            val f = File("libs${File.separator}${CommonConf.frameworkFileName}.jar")
            if (f.exists()) ZipUtility.unzip(f.canonicalPath,frameworkDir.canonicalPath)
            else ZipUtility.unzip(ClassLoader.getSystemClassLoader().getResource(CommonConf.frameworkFileName)!!.openStream(),frameworkDir.canonicalPath)
        }
    }

    private fun androidFindClass(className: String):File?=
        findFromAndroidFramework(className)?:findFromApkFile(className)


    private fun findFromDir(dir:File,className:String):File?{
        val filePath = className.replace('.','/')+".class"
        val f = File(dir,filePath)
        if (f.exists()) return f
        return null
    }

    private fun findFromApkFile(className: String):File? =
        findFromDir(apkFile.classesDir,className)

    private fun findFromAndroidFramework(className: String): File? =
        findFromDir(File(CommonConf.workDir+File.separator+CommonConf.tempDirName+File.separator+CommonConf.frameworkDirName),className)


    private fun loadClass(file: File): Class<*> {
        val data = androidRuntimeClass.afterResolveClass(
            this,ClassPool.getDefault().makeClass(file.inputStream(),false)
        ).toBytecode()
        return androidLoader.xDefineClass(null,data,0,data.size)
    }

    /**
     * look for it from apk path
     * @param className 形如 com.example.myapplication.TestJava
     * @return class类对象
     */
    fun loadClass(className: String): Class<*> {
        val mClassName = className.replace("[]","")
        return androidRuntimeClass.beforeResolveClass(this,mClassName,androidLoader)
           ?:loadClass(androidFindClass(mClassName)?:throw FrameWorkClassNoFoundException("$mClassName not find from frame work")).apply { logger.trace("$this loaded!") }
    }

    /**
     * look for it from this project
     * this will not pass the resolve-callback
     * @param className 形如 com.example.myapplication.TestJava
     * @return class类对象
     */
    fun loadClassProject(className: String): Class<*>{
        val ctClass = ClassPool.getDefault().getCtClass(className)
        return ctClass.toBytecode().run {
            ctClass.defrost()
            androidLoader.xDefineClass(null,this,0,size)
        }
    }

    fun findClass(name: String)
        = Class.forName(name,false,androidLoader)

    /**
     * @param signature which looks like xxxxx
     * @param implemented if it is ture the method while be replace by your method
     */
    fun registerMethodHook(signature:String,replace:Boolean)
        = DbgContext.registerMethodHook(id,signature,replace)

    fun addAfterClassInterceptor(classInterceptorBase: RuntimeClassInterceptorBase){
        androidRuntimeClass.addAfterClassInterceptor(classInterceptorBase)
    }

    fun loadLibrary(soName:String,fullPath:Boolean){
        if (methodInterceptor is NativeImplementInterceptor) methodInterceptor.loadLibrary(this,soName,fullPath)
        else throw Exception("$methodInterceptor is not instanceof NativeImplementInterceptor!")
    }


    fun setConversationHandler(appdbgConversationSchemaEnum: AppdbgConversationSchemaEnum,
                               iAppdbgConversationHandler: IAppdbgConversationHandler){
        if (this.conversationHandlerMap.containsKey(appdbgConversationSchemaEnum)){
            val ins = this.getConversationHandler(appdbgConversationSchemaEnum)
            logger.warn("${appdbgConversationSchemaEnum} already has instance ${ins},which you do will overwrite the ins!")
        }
        this.conversationHandlerMap[appdbgConversationSchemaEnum] = iAppdbgConversationHandler
    }

    fun getConversationHandler(appdbgConversationSchemaEnum: AppdbgConversationSchemaEnum) =
        this.conversationHandlerMap[appdbgConversationSchemaEnum]

    override fun toString(): String =
        "${javaClass.simpleName}[$processName ]"

}