package es.mitgcf.app;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Mi TGCF — contenedor nativo de la aplicación.
 *
 * La aplicación entera es un único archivo HTML que vive en assets/. Se sirve a través de
 * WebViewAssetLoader bajo un origen https real, y no file://, para que el almacenamiento
 * local del navegador funcione de forma fiable y los datos del usuario no se pierdan.
 *
 * Un WebView pelado NO sabe atender los avisos, las preguntas de sí/no, los cuadros de
 * texto, la selección de archivos ni las descargas de la página: hay que resolverlos aquí
 * con diálogos y ficheros nativos. De eso se ocupa el WebChromeClient y el puente
 * AndroidArchivo.
 *
 * Los planes de entrenamiento se desbloquean con una compra única gestionada por la
 * Facturación de Google Play, a través del puente AndroidBilling.
 *
 * Maikel Rodríguez Domínguez.
 */
public class MainActivity extends AppCompatActivity implements PurchasesUpdatedListener {

    /** Identificador del producto tal y como se dé de alta en Play Console. */
    private static final String PRODUCTO = "planes_entrenamiento";

    private static final String ORIGEN = "https://appassets.androidplatform.net";
    private static final int NAVY = 0xFF0E1738;

    private WebView web;
    /* Altura de las barras del sistema, en píxeles de CSS. La página las usa para que
       la cabecera no quede debajo del reloj ni de la batería. */
    private int margenArriba = 0;
    private int margenAbajo  = 0;
    private BillingClient facturacion;
    private final Handler hilo = new Handler(Looper.getMainLooper());

    private volatile boolean comprado = false;
    private volatile boolean listo = false;
    private volatile String precio = "";
    private ProductDetails detalles = null;

    /** Selección de archivos para los <input type="file"> de la página. */
    private ValueCallback<Uri[]> esperandoArchivos;
    private final ActivityResultLauncher<Intent> selectorArchivos =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (esperandoArchivos == null) return;
                esperandoArchivos.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData()));
                esperandoArchivos = null;
            });

    // ----------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(NAVY);
        raiz.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(NAVY);
        raiz.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(raiz);

        // A partir de Android 15 el contenido se dibuja bajo las barras del sistema.
        // Medimos su altura aquí y se la pasamos a la página como variables de CSS.
        // No usamos env(safe-area-inset-*) porque dentro de una WebView de Android
        // devuelve cero aunque el contenido esté tapado: está pensada para el notch
        // del iPhone y Android no la rellena de forma fiable.
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            Insets b = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            float d = getResources().getDisplayMetrics().density;
            margenArriba = Math.round(b.top / d);
            margenAbajo  = Math.round(b.bottom / d);
            aplicaMargenes();
            return insets;
        });
        ViewCompat.requestApplyInsets(web);

        configuraWeb();
        conectaFacturacion();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { atras(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        consultaCompras();   // recupera compras hechas en otro dispositivo o tras reinstalar
    }

    @Override
    protected void onDestroy() {
        if (facturacion != null) {
            try { facturacion.endConnection(); } catch (Exception ignorada) { }
        }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ----------------------------------------------------------------- WebView

    private void configuraWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Puente(), "AndroidBilling");
        web.addJavascriptInterface(new Archivos(), "AndroidArchivo");

        final WebViewAssetLoader cargador = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest p) {
                return cargador.shouldInterceptRequest(p.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest p) {
                Uri u = p.getUrl();
                if (u != null && ORIGEN.equals(u.getScheme() + "://" + u.getHost())) return false;
                abreFuera(u);   // los vídeos y enlaces externos salen al navegador
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                avisaWeb();
                aplicaMargenes();   // la página acaba de cargar: hay que volver a dárselos
            }
        });

        // Sin esto, la página no puede avisar, preguntar, pedir un dato ni abrir archivos.
        web.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onJsAlert(WebView v, String url, String mensaje, JsResult res) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(mensaje)
                        .setPositiveButton("Aceptar", (d, w) -> res.confirm())
                        .setOnCancelListener(d -> res.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String mensaje, JsResult res) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(mensaje)
                        .setPositiveButton("Aceptar", (d, w) -> res.confirm())
                        .setNegativeButton("Cancelar", (d, w) -> res.cancel())
                        .setOnCancelListener(d -> res.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String mensaje,
                                      String pordefecto, JsPromptResult res) {
                final EditText campo = new EditText(MainActivity.this);
                campo.setSingleLine(true);
                if (pordefecto != null) campo.setText(pordefecto);
                int m = (int) (18 * getResources().getDisplayMetrics().density);
                FrameLayout caja = new FrameLayout(MainActivity.this);
                caja.setPadding(m, m / 2, m, 0);
                caja.addView(campo);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(mensaje)
                        .setView(caja)
                        .setPositiveButton("Aceptar", (d, w) -> res.confirm(campo.getText().toString()))
                        .setNegativeButton("Cancelar", (d, w) -> res.cancel())
                        .setOnCancelListener(d -> res.cancel())
                        .show();
                campo.requestFocus();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (esperandoArchivos != null) esperandoArchivos.onReceiveValue(null);
                esperandoArchivos = cb;
                try {
                    selectorArchivos.launch(params.createIntent());
                } catch (Exception e) {
                    esperandoArchivos = null;
                    aviso("No se ha podido abrir el selector de archivos.");
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(ORIGEN + "/assets/index.html");
    }

    private void abreFuera(Uri u) {
        if (u == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignorada) { }
    }

    private void aviso(String t) {
        hilo.post(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show());
    }

    /** El botón «atrás» vuelve a Inicio; si ya está en Inicio, sale de la aplicación. */
    private void atras() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript(
                "(function(){try{if(typeof vista!=='undefined'&&vista!=='inicio'){setVista('inicio');return '1';}}catch(e){}return '0';})()",
                valor -> {
                    String v = valor == null ? "0" : valor.replace("\"", "").trim();
                    if (!"1".equals(v)) finish();
                });
    }

    // ----------------------------------------------------------------- guardar archivos

    /** Escribe en la carpeta Descargas del móvil. No necesita permisos en Android 10 o superior. */
    public class Archivos {
        @JavascriptInterface
        public String guardar(String nombre, String contenido, String tipo) {
            if (nombre == null || nombre.isEmpty()) nombre = "mi-tgcf.txt";
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, nombre);
                cv.put(MediaStore.Downloads.MIME_TYPE,
                        (tipo == null || tipo.isEmpty()) ? "application/octet-stream" : tipo);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri destino = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (destino == null) return "";

                OutputStream os = getContentResolver().openOutputStream(destino);
                if (os == null) return "";
                os.write(contenido == null ? new byte[0] : contenido.getBytes(StandardCharsets.UTF_8));
                os.close();

                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(destino, cv, null, null);
                return nombre;
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ----------------------------------------------------------------- facturación

    private void conectaFacturacion() {
        facturacion = BillingClient.newBuilder(this)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
        arranca(0);
    }

    private void arranca(final int intento) {
        facturacion.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    listo = true;
                    consultaDetalles();
                    consultaCompras();
                } else {
                    reintenta(intento);
                }
            }
            @Override
            public void onBillingServiceDisconnected() { listo = false; reintenta(intento); }
        });
    }

    private void reintenta(int intento) {
        if (intento >= 4) { avisaWeb(); return; }
        hilo.postDelayed(() -> arranca(intento + 1), 1000L * (1L << intento));
    }

    private void consultaDetalles() {
        QueryProductDetailsParams p = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(PRODUCTO)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()))
                .build();

        facturacion.queryProductDetailsAsync(p, (r, lista) -> {
            if (r.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && lista != null && !lista.isEmpty()) {
                detalles = lista.get(0);
                ProductDetails.OneTimePurchaseOfferDetails o = detalles.getOneTimePurchaseOfferDetails();
                if (o != null) precio = o.getFormattedPrice();
            }
            avisaWeb();
        });
    }

    private void consultaCompras() {
        if (facturacion == null || !facturacion.isReady()) return;
        facturacion.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP).build(),
                (r, compras) -> {
                    boolean encontrada = false;
                    if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && compras != null) {
                        for (Purchase c : compras) {
                            if (esNuestra(c) && c.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                encontrada = true;
                                confirma(c);
                            }
                        }
                    }
                    comprado = encontrada;
                    avisaWeb();
                });
    }

    private boolean esNuestra(Purchase c) {
        List<String> ids = c.getProducts();
        return ids != null && ids.contains(PRODUCTO);
    }

    /** Google cancela y devuelve el dinero de las compras que no se confirman en 3 días. */
    private void confirma(Purchase c) {
        if (c.isAcknowledged()) return;
        facturacion.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(c.getPurchaseToken()).build(), r -> { });
    }

    @Override
    public void onPurchasesUpdated(BillingResult r, List<Purchase> compras) {
        if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && compras != null) {
            for (Purchase c : compras) {
                if (esNuestra(c) && c.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    comprado = true;
                    confirma(c);
                }
            }
        } else if (r.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            comprado = true;
        }
        avisaWeb();
    }

    private void lanzaCompra() {
        if (facturacion == null || !facturacion.isReady()) { avisaWeb(); return; }
        if (detalles == null) { consultaDetalles(); return; }

        BillingFlowParams p = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(detalles).build()))
                .build();
        facturacion.launchBillingFlow(this, p);
    }

    // ----------------------------------------------------------------- puente

    private String estadoJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("comprado", comprado);
            j.put("listo", listo);
            j.put("precio", precio == null ? "" : precio);
        } catch (Exception ignorada) { }
        return j.toString();
    }

    /**
     * Da a la página la altura real de las barras del sistema, en píxeles de CSS.
     * El HTML la lee en --sat y --sab. Se llama cada vez que Android informa de los
     * márgenes y también al terminar de cargar la página, porque el primer aviso puede
     * llegar antes de que exista el documento.
     */
    private void aplicaMargenes() {
        final String js = "document.documentElement.style.setProperty('--sat','" + margenArriba + "px');"
                        + "document.documentElement.style.setProperty('--sab','" + margenAbajo  + "px');";
        hilo.post(() -> {
            if (web == null) return;
            web.evaluateJavascript(js, null);
        });
    }

    /** Empuja el estado hacia la página para que repinte el muro de pago o el plan. */
    private void avisaWeb() {
        final String json = estadoJson();
        hilo.post(() -> {
            if (web == null) return;
            web.evaluateJavascript(
                    "window.billingCambia && window.billingCambia(" + JSONObject.quote(json) + ")", null);
        });
    }

    public class Puente {
        @JavascriptInterface
        public String estado() { return estadoJson(); }

        @JavascriptInterface
        public void comprar() { hilo.post(MainActivity.this::lanzaCompra); }

        @JavascriptInterface
        public void restaurar() { hilo.post(MainActivity.this::consultaCompras); }
    }
}
