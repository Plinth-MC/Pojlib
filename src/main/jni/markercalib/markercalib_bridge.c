//
// HoloCraft marker (AprilTag) table calibration: Minecraft JVM -> Android bridge.
//
// The Minecraft JVM has no android.* classes. Like Visor's QuestVLoader (vloader.cpp), the add-on
// declares static natives on its own class (org.vmstudio.holocraft.client.markercalib.QuestCameraBridge,
// loaded with System.loadLibrary("holocraft_markercalib")); they are implemented here and forward to
// pojlib.markercalib.PassthroughCamera in the Android (ART) VM of the same process.
//
// The calling thread is attached to the Android VM for the call (and detached again if it was not
// attached before). PassthroughCamera is resolved through the activity's class loader, since
// FindClass on an attached native thread only sees the system class loader.
//
#include <jni.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include <environ/environ.h>

#define TAG "HoloCraftMarkerCalib"
#define LOG_W(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define CAMERA_CLASS "pojlib.markercalib.PassthroughCamera"

static pthread_mutex_t lookup_lock = PTHREAD_MUTEX_INITIALIZER;
static jclass camera_class;
static jmethodID m_start, m_stop, m_get_status, m_get_message, m_get_calibration, m_copy_latest_frame;

static JNIEnv *attach_android(int *attached) {
    *attached = 0;
    JavaVM *vm = pojav_environ->dalvikJavaVMPtr;
    if (vm == NULL) {
        return NULL;
    }
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
        *attached = 1;
    }
    return env;
}

static void detach_android(int attached) {
    if (attached) {
        JavaVM *vm = pojav_environ->dalvikJavaVMPtr;
        (*vm)->DetachCurrentThread(vm);
    }
}

static int clear_exception(JNIEnv *env, const char *what) {
    if ((*env)->ExceptionCheck(env)) {
        LOG_W("exception in %s", what);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

/** Resolve PassthroughCamera and its methods once (Android env) */
static int ensure_class(JNIEnv *env) {
    pthread_mutex_lock(&lookup_lock);
    if (camera_class != NULL) {
        pthread_mutex_unlock(&lookup_lock);
        return 1;
    }
    int ok = 0;
    jobject activity = pojav_environ->activity;
    if (activity != NULL) {
        jclass activity_class = (*env)->GetObjectClass(env, activity);
        jmethodID get_loader = (*env)->GetMethodID(env, activity_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject loader = (*env)->CallObjectMethod(env, activity, get_loader);
        if (!clear_exception(env, "getClassLoader") && loader != NULL) {
            jclass loader_class = (*env)->FindClass(env, "java/lang/ClassLoader");
            jmethodID load_class = (*env)->GetMethodID(env, loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            jstring name = (*env)->NewStringUTF(env, CAMERA_CLASS);
            jclass cls = (jclass) (*env)->CallObjectMethod(env, loader, load_class, name);
            if (!clear_exception(env, "loadClass " CAMERA_CLASS) && cls != NULL) {
                m_start = (*env)->GetStaticMethodID(env, cls, "start", "(Landroid/app/Activity;III)I");
                m_stop = (*env)->GetStaticMethodID(env, cls, "stop", "()V");
                m_get_status = (*env)->GetStaticMethodID(env, cls, "getStatus", "()I");
                m_get_message = (*env)->GetStaticMethodID(env, cls, "getMessage", "()Ljava/lang/String;");
                m_get_calibration = (*env)->GetStaticMethodID(env, cls, "getCalibration", "()[F");
                m_copy_latest_frame = (*env)->GetStaticMethodID(env, cls, "copyLatestFrame", "(Ljava/nio/ByteBuffer;J)[J");
                if (!clear_exception(env, "GetStaticMethodID")) {
                    camera_class = (jclass) (*env)->NewGlobalRef(env, cls);
                    ok = 1;
                }
                (*env)->DeleteLocalRef(env, cls);
            }
            (*env)->DeleteLocalRef(env, name);
            (*env)->DeleteLocalRef(env, loader_class);
            (*env)->DeleteLocalRef(env, loader);
        }
        (*env)->DeleteLocalRef(env, activity_class);
    } else {
        LOG_W("no activity (VLoader.setAndroidInitInfo not called)");
    }
    pthread_mutex_unlock(&lookup_lock);
    return ok;
}

#define BRIDGE(ret, name) JNIEXPORT ret JNICALL Java_org_vmstudio_holocraft_client_markercalib_QuestCameraBridge_##name

/** @return status, or -2 (unavailable) if the Android side can't be reached */
BRIDGE(jint, nativeStart)(JNIEnv *mcEnv, jclass clazz, jint position, jint width, jint height) {
    int attached;
    JNIEnv *env = attach_android(&attached);
    if (env == NULL) {
        return -2;
    }
    jint status = -2;
    if (ensure_class(env)) {
        status = (*env)->CallStaticIntMethod(env, camera_class, m_start, pojav_environ->activity,
                                             position, width, height);
        if (clear_exception(env, "start")) {
            status = -3;
        }
    }
    detach_android(attached);
    return status;
}

BRIDGE(void, nativeStop)(JNIEnv *mcEnv, jclass clazz) {
    int attached;
    JNIEnv *env = attach_android(&attached);
    if (env == NULL) {
        return;
    }
    if (ensure_class(env)) {
        (*env)->CallStaticVoidMethod(env, camera_class, m_stop);
        clear_exception(env, "stop");
    }
    detach_android(attached);
}

BRIDGE(jint, nativeGetStatus)(JNIEnv *mcEnv, jclass clazz) {
    int attached;
    JNIEnv *env = attach_android(&attached);
    if (env == NULL) {
        return -2;
    }
    jint status = -2;
    if (ensure_class(env)) {
        status = (*env)->CallStaticIntMethod(env, camera_class, m_get_status);
        if (clear_exception(env, "getStatus")) {
            status = -3;
        }
    }
    detach_android(attached);
    return status;
}

BRIDGE(jstring, nativeGetMessage)(JNIEnv *mcEnv, jclass clazz) {
    int attached;
    JNIEnv *env = attach_android(&attached);
    if (env == NULL) {
        return (*mcEnv)->NewStringUTF(mcEnv, "Android VM not available");
    }
    jstring result = NULL;
    if (ensure_class(env)) {
        jstring message = (jstring) (*env)->CallStaticObjectMethod(env, camera_class, m_get_message);
        if (!clear_exception(env, "getMessage") && message != NULL) {
            const char *chars = (*env)->GetStringUTFChars(env, message, NULL);
            if (chars != NULL) {
                result = (*mcEnv)->NewStringUTF(mcEnv, chars);
                (*env)->ReleaseStringUTFChars(env, message, chars);
            }
            (*env)->DeleteLocalRef(env, message);
        }
    } else {
        result = (*mcEnv)->NewStringUTF(mcEnv, "pojlib.markercalib.PassthroughCamera not found (old launcher build?)");
    }
    detach_android(attached);
    return result;
}

/** Copy the calibration floats into out (Minecraft JVM array) @return true if available */
BRIDGE(jboolean, nativeGetCalibration)(JNIEnv *mcEnv, jclass clazz, jfloatArray out) {
    int attached;
    JNIEnv *env = attach_android(&attached);
    if (env == NULL) {
        return JNI_FALSE;
    }
    jboolean ok = JNI_FALSE;
    if (ensure_class(env)) {
        jfloatArray calibration = (jfloatArray) (*env)->CallStaticObjectMethod(env, camera_class, m_get_calibration);
        if (!clear_exception(env, "getCalibration") && calibration != NULL) {
            jsize n = (*env)->GetArrayLength(env, calibration);
            jsize m = (*mcEnv)->GetArrayLength(mcEnv, out);
            jsize count = n < m ? n : m;
            jfloat buf[64];
            if (count > 64) {
                count = 64;
            }
            (*env)->GetFloatArrayRegion(env, calibration, 0, count, buf);
            (*mcEnv)->SetFloatArrayRegion(mcEnv, out, 0, count, buf);
            (*env)->DeleteLocalRef(env, calibration);
            ok = JNI_TRUE;
        }
    }
    detach_android(attached);
    return ok;
}

/**
 * Copy the latest Y plane into dst (a direct ByteBuffer of the Minecraft JVM): the Android side writes
 * straight into that memory through a direct buffer created over the same address
 *
 * @return true if a frame newer than afterSeq was copied (metadata in metaOut)
 */
BRIDGE(jboolean, nativeCopyLatestFrame)(JNIEnv *mcEnv, jclass clazz, jobject dst, jlong afterSeq, jlongArray metaOut) {
    void *address = (*mcEnv)->GetDirectBufferAddress(mcEnv, dst);
    jlong capacity = (*mcEnv)->GetDirectBufferCapacity(mcEnv, dst);
    if (address == NULL || capacity <= 0) {
        return JNI_FALSE;
    }
    int attached;
    JNIEnv *env = attach_android(&attached);
    if (env == NULL) {
        return JNI_FALSE;
    }
    jboolean ok = JNI_FALSE;
    if (ensure_class(env)) {
        jobject view = (*env)->NewDirectByteBuffer(env, address, capacity);
        if (view != NULL) {
            jlongArray meta = (jlongArray) (*env)->CallStaticObjectMethod(env, camera_class, m_copy_latest_frame,
                                                                          view, afterSeq);
            if (!clear_exception(env, "copyLatestFrame") && meta != NULL) {
                jsize n = (*env)->GetArrayLength(env, meta);
                jsize m = (*mcEnv)->GetArrayLength(mcEnv, metaOut);
                jsize count = n < m ? n : m;
                jlong buf[16];
                if (count > 16) {
                    count = 16;
                }
                (*env)->GetLongArrayRegion(env, meta, 0, count, buf);
                (*mcEnv)->SetLongArrayRegion(mcEnv, metaOut, 0, count, buf);
                (*env)->DeleteLocalRef(env, meta);
                ok = JNI_TRUE;
            }
            (*env)->DeleteLocalRef(env, view);
        }
    }
    detach_android(attached);
    return ok;
}
