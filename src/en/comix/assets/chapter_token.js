// Hands each chapter-list request to the extension instead of letting the page send it.
//
// The page still builds and signs the request, so the token is the site's own. The request is then
// held open, the extension performs it natively, and the response is dropped back into the same
// XHR (or resolves the same fetch promise), so the site decrypts it exactly as it would a response
// of its own and capture_items.js still sees the decoded payload.
//
// Installed by evaluating this file; the extension then calls:
//   __comixTake()                 -> { url, transport } while a request is held, else null
//   __comixDeliver(url, body)     -> completes the held request with the natively fetched body
//   __comixFail(url, status, why) -> completes it with an error instead
(() => {
    if (window.__comixTake) return;

    const isApi = (url) => url && url.includes("/api/v1/");
    const tokenOf = (url) => (/(^|[?&])_=[^&"']+/.exec(url) || [""])[0];

    let held = null;

    const notify = (pending) => {
        try {
            if (window.__comixOnHeld) window.__comixOnHeld(pending.url, pending.transport);
        } catch (e) {
            /* the extension cannot be told; the request simply stays held until the page gives up */
        }
    };

    const realOpen = XMLHttpRequest.prototype.open;
    const realSend = XMLHttpRequest.prototype.send;
    const realFetch = window.fetch;

    XMLHttpRequest.prototype.open = function (method, url, ...rest) {
        this.__comixUrl = String(url);
        return realOpen.call(this, method, url, ...rest);
    };

    XMLHttpRequest.prototype.send = function (body) {
        const url = this.__comixUrl;
        if (!isApi(url)) return realSend.call(this, body);

        // Held: the extension completes this, so nothing leaves from the page
        this.__comixBody = body;
        held = { url, transport: "xhr", xhr: this, token: tokenOf(url) };
        notify(held);
    };

    window.fetch = function (input, init) {
        const url = typeof input === "string" ? input : (input && input.url);
        if (!isApi(url)) return realFetch.call(this, input, init);

        held = { url, transport: "fetch", resolve: null, reject: null, token: tokenOf(url) };
        notify(held);
        return new Promise((resolve, reject) => {
            held.resolve = resolve;
            held.reject = reject;
        });
    };

    window.__comixTake = () => (held ? { url: held.url, transport: held.transport, token: held.token } : null);

    window.__comixDeliver = (url, body, status) => {
        if (!held || held.url !== url) return false;
        const current = held;
        held = null;
        try {
            if (current.transport === "fetch") {
                current.resolve(new Response(body, { status: status || 200 }));
            } else {
                const xhr = current.xhr;
                const define = (key, value) => Object.defineProperty(xhr, key, { value, configurable: true });
                define("readyState", 4);
                define("status", status || 200);
                define("responseText", body);
                define("response", body);
                define("responseURL", current.url);
                xhr.dispatchEvent(new ProgressEvent("readystatechange"));
                xhr.dispatchEvent(new ProgressEvent("load"));
                xhr.dispatchEvent(new ProgressEvent("loadend"));
            }
            return true;
        } catch (e) {
            return false;
        }
    };

    window.__comixFail = (url, why) => {
        if (!held || held.url !== url) return false;
        const current = held;
        held = null;
        if (current.transport === "fetch") {
            current.reject(new Error(why || "failed"));
        } else {
            try {
                current.xhr.dispatchEvent(new ProgressEvent("error"));
                current.xhr.dispatchEvent(new ProgressEvent("loadend"));
            } catch (e) {
                /* nothing useful to do if the page has already given up on it */
            }
        }
        return true;
    };

    window.__comixTake.pending = () => held;
})();
