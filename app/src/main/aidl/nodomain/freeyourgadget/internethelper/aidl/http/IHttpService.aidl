package nodomain.freeyourgadget.internethelper.aidl.http;

import nodomain.freeyourgadget.internethelper.aidl.http.HttpRequest;
import nodomain.freeyourgadget.internethelper.aidl.http.IHttpCallback;

interface IHttpService {
    int version();

    void send(in HttpRequest request, IHttpCallback cb);
    void sendOnNetwork(in HttpRequest request, String networkRequestId, IHttpCallback cb);

    /**
     * Like sendOnNetwork, with networkRequestId null for the default network. timeoutMs replaces
     * the default 10 s read and write timeouts (since version 3).
     */
    void sendWithTimeout(in HttpRequest request, @nullable String networkRequestId, long timeoutMs, IHttpCallback cb);
}
