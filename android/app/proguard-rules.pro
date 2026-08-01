# La interfaz JavaScript se llama por reflexión desde la página: no se puede ofuscar.
-keepclassmembers class es.mitgcf.app.MainActivity$Puente {
    public *;
}
-keepattributes JavascriptInterface
