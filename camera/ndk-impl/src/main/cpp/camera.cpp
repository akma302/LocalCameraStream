#include <jni.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadataTags.h>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

namespace {
JavaVM* vm = nullptr;
struct Env {
    JNIEnv* env = nullptr;
    bool attached = false;
    Env() {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
struct Utf {
    JNIEnv* env; jstring value; const char* text;
    Utf(JNIEnv* e, jstring s) : env(e), value(s), text(e->GetStringUTFChars(s, nullptr)) {}
    ~Utf() { env->ReleaseStringUTFChars(value, text); }
};
void error(JNIEnv* env, const std::string& message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}
using Manager = std::unique_ptr<ACameraManager, decltype(&ACameraManager_delete)>;
using Metadata = std::unique_ptr<ACameraMetadata, decltype(&ACameraMetadata_free)>;
Metadata metadata(ACameraManager* manager, const char* id) {
    ACameraMetadata* ptr = nullptr;
    ACameraManager_getCameraCharacteristics(manager, id, &ptr);
    return Metadata(ptr, ACameraMetadata_free);
}
ACameraMetadata_const_entry entry(const ACameraMetadata* m, uint32_t tag) {
    ACameraMetadata_const_entry e{};
    if (m) ACameraMetadata_getConstEntry(m, tag, &e);
    return e;
}
struct Capture {
    ACameraManager* manager = nullptr;
    ACameraDevice* device = nullptr;
    ACameraCaptureSession* session = nullptr;
    ACaptureRequest* request = nullptr;
    ACaptureSessionOutputContainer* outputs = nullptr;
    ACaptureSessionOutput* output = nullptr;
    ACameraOutputTarget* target = nullptr;
    ANativeWindow* window = nullptr;
    jobject callback = nullptr;
    jmethodID onError = nullptr;
    std::atomic<bool> closing{false};
    void notify(int code) {
        if (closing.load()) return;
        Env e;
        if (e.env && callback) {
            e.env->CallVoidMethod(callback, onError, code);
            if (e.env->ExceptionCheck()) e.env->ExceptionClear();
        }
    }
    void close() {
        if (closing.exchange(true)) return;
        if (session) {
            ACameraCaptureSession_stopRepeating(session);
            ACameraCaptureSession_close(session);
        }
        if (device) { ACameraDevice_close(device); device = nullptr; }
    }
    ~Capture() {
        // The capture-session callback owns a shared_ptr until onClosed. Its final
        // reference keeps output windows and the JNI global reference alive.
        if (target) ACameraOutputTarget_free(target);
        if (request) ACaptureRequest_free(request);
        if (output) ACaptureSessionOutput_free(output);
        if (outputs) ACaptureSessionOutputContainer_free(outputs);
        if (window) ANativeWindow_release(window);
        if (manager) ACameraManager_delete(manager);
        if (callback) { Env e; if (e.env) e.env->DeleteGlobalRef(callback); }
    }
};
using Holder = std::shared_ptr<Capture>;
void disconnected(void* context, ACameraDevice*) { static_cast<Capture*>(context)->notify(-1); }
void deviceError(void* context, ACameraDevice*, int code) { static_cast<Capture*>(context)->notify(code); }
void sessionClosed(void* context, ACameraCaptureSession*) { delete static_cast<Holder*>(context); }
void sessionState(void*, ACameraCaptureSession*) {}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVm, void*) { vm = javaVm; return JNI_VERSION_1_6; }

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_localstream_camera_ndk_NdkCameraApi_nativeCameras(JNIEnv* env, jobject) {
    Manager manager(ACameraManager_create(), ACameraManager_delete);
    ACameraIdList* ids = nullptr;
    if (ACameraManager_getCameraIdList(manager.get(), &ids) != ACAMERA_OK) { error(env, "Не удалось перечислить NDK камеры"); return nullptr; }
    auto array = env->NewObjectArray(ids->numCameras, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < ids->numCameras; ++i) {
        auto m = metadata(manager.get(), ids->cameraIds[i]);
        auto facing = entry(m.get(), ACAMERA_LENS_FACING);
        auto orientation = entry(m.get(), ACAMERA_SENSOR_ORIENTATION);
        std::string value = std::string(ids->cameraIds[i]) + "|" + std::to_string(facing.count ? facing.data.u8[0] : 2)
            + "|" + std::to_string(orientation.count ? orientation.data.i32[0] : 0);
        auto str = env->NewStringUTF(value.c_str());
        env->SetObjectArrayElement(array, i, str); env->DeleteLocalRef(str);
    }
    ACameraManager_deleteCameraIdList(ids);
    return array;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_localstream_camera_ndk_NdkCameraApi_nativeProfiles(JNIEnv* env, jobject, jstring id) {
    Utf cameraId(env, id);
    Manager manager(ACameraManager_create(), ACameraManager_delete);
    auto m = metadata(manager.get(), cameraId.text);
    if (!m) { error(env, "Нет характеристик NDK камеры"); return nullptr; }
    auto streams = entry(m.get(), ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS);
    auto ranges = entry(m.get(), ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    auto durations = entry(m.get(), ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS);
    std::vector<jint> result;
    for (uint32_t i = 0; i + 3 < streams.count; i += 4) {
        const auto* s = streams.data.i32 + i;
        if (s[0] != 34 || s[3] != 0) continue; // IMPLEMENTATION_DEFINED / PRIVATE output
        int64_t duration = 0;
        for (uint32_t j = 0; j + 3 < durations.count; j += 4) {
            const auto* d = durations.data.i64 + j;
            if (d[0] == s[0] && d[1] == s[1] && d[2] == s[2]) duration = d[3];
        }
        for (uint32_t j = 0; j + 1 < ranges.count; j += 2) {
            int lo = ranges.data.i32[j], hi = ranges.data.i32[j + 1];
            if (hi > 0 && hi <= 30 && (duration == 0 || duration <= 1000000000LL / hi + 1000000LL))
                result.insert(result.end(), {s[1], s[2], lo, hi});
        }
    }
    auto array = env->NewIntArray(static_cast<jsize>(result.size()));
    if (!result.empty()) env->SetIntArrayRegion(array, 0, result.size(), result.data());
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localstream_camera_ndk_NdkCameraApi_nativeStart(JNIEnv* env, jobject, jstring id, jobject surface,
    jint width, jint height, jint minFps, jint maxFps, jobject callback) {
    Utf cameraId(env, id);
    auto state = std::make_shared<Capture>();
    state->manager = ACameraManager_create();
    state->window = ANativeWindow_fromSurface(env, surface);
    state->callback = env->NewGlobalRef(callback);
    state->onError = env->GetMethodID(env->GetObjectClass(callback), "onNativeError", "(I)V");
    if (!state->window || !state->onError) { error(env, "Недействительная NDK поверхность"); return 0; }
    // SurfaceTextureHelper sets the buffer size before this call; never change its format.
    if (ANativeWindow_getWidth(state->window) != width || ANativeWindow_getHeight(state->window) != height) {
        error(env, "Размер Surface не соответствует профилю камеры"); return 0;
    }
    ACameraDevice_StateCallbacks callbacks{state.get(), disconnected, deviceError};
    camera_status_t status = ACameraManager_openCamera(state->manager, cameraId.text, &callbacks, &state->device);
    if (status == ACAMERA_OK) status = ACaptureSessionOutputContainer_create(&state->outputs);
    if (status == ACAMERA_OK) status = ACaptureSessionOutput_create(state->window, &state->output);
    if (status == ACAMERA_OK) status = ACaptureSessionOutputContainer_add(state->outputs, state->output);
    if (status == ACAMERA_OK) status = ACameraDevice_createCaptureRequest(state->device, TEMPLATE_RECORD, &state->request);
    if (status == ACAMERA_OK) status = ACameraOutputTarget_create(state->window, &state->target);
    if (status == ACAMERA_OK) status = ACaptureRequest_addTarget(state->request, state->target);
    int32_t fps[] = {minFps, maxFps};
    if (status == ACAMERA_OK) status = ACaptureRequest_setEntry_i32(state->request, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, fps);
    auto m = metadata(state->manager, cameraId.text);
    auto modes = entry(m.get(), ACAMERA_CONTROL_AF_AVAILABLE_MODES);
    for (uint32_t i = 0; i < modes.count; ++i) {
        if (modes.data.u8[i] == ACAMERA_CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
            uint8_t mode = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            ACaptureRequest_setEntry_u8(state->request, ACAMERA_CONTROL_AF_MODE, 1, &mode); break;
        }
    }
    if (status == ACAMERA_OK) {
        auto lifetime = new Holder(state);
        ACameraCaptureSession_stateCallbacks cb{lifetime, sessionClosed, sessionState, sessionState};
        status = ACameraDevice_createCaptureSession(state->device, state->outputs, &cb, &state->session);
        if (status != ACAMERA_OK) delete lifetime;
    }
    if (status == ACAMERA_OK) status = ACameraCaptureSession_setRepeatingRequest(state->session, nullptr, 1, &state->request, nullptr);
    if (status != ACAMERA_OK) {
        state->close(); error(env, "Ошибка Camera NDK: " + std::to_string(status)); return 0;
    }
    return reinterpret_cast<jlong>(new Holder(state));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localstream_camera_ndk_NdkCameraApi_nativeClose(JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    std::unique_ptr<Holder> holder(reinterpret_cast<Holder*>(handle));
    (*holder)->close();
}
