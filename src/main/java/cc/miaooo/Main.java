package cc.miaooo;

import io.kubernetes.client.PodLogs;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.CallGeneratorParams;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public class Main {

    public static void main(String[] args) throws Exception {
        var client = Config.fromConfig("dev.yaml");
        Configuration.setDefaultApiClient(client);

        CoreV1Api coreV1Api = new CoreV1Api(client);
        ApiClient apiClient = coreV1Api.getApiClient();
        OkHttpClient httpClient =
           apiClient.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
        apiClient.setHttpClient(httpClient);

        SharedInformerFactory factory = new SharedInformerFactory(apiClient);

        // Node informer
        SharedIndexInformer<V1Pod> nodeInformer =
           factory.sharedIndexInformerFor(
              // **NOTE**:
              // The following "CallGeneratorParams" lambda merely generates a stateless
              // HTTPs requests, the effective apiClient is the one specified when constructing
              // the informer-factory.
              (CallGeneratorParams params) -> {
                  return coreV1Api.listNamespacedPodCall(
                     "dev",
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     params.resourceVersion,
                     null,
                     params.timeoutSeconds,
                     params.watch,
                     null);
              },
              V1Pod.class,
              V1PodList.class);

        final var podLogs = new PodLogs();
        final var map = new ConcurrentHashMap<String, V1Pod>();

        nodeInformer.addEventHandler(
           new ResourceEventHandler<V1Pod>() {
               @Override
               public void onAdd(V1Pod pod) {
                   System.out.printf("%s pod added!\n", pod.getMetadata().getName());
               }

               @Override
               public void onUpdate(V1Pod oldPod, V1Pod newPod) {
                   final var name = newPod.getMetadata().getName();
                   System.out.printf(
                      "%s => %s pod updated!\n",
                      oldPod.getMetadata().getName(), name);
                   if (!map.contains(name)) {
                       map.put(Objects.requireNonNull(name), newPod);
                       final var thread = new Thread(() -> {
                           final InputStream inputStream;
                           try {
                               inputStream = podLogs.streamNamespacedPodLog(newPod);
                               Streams.copy(inputStream, System.out);
                           } catch (ApiException e) {
                               throw new RuntimeException(e);
                           } catch (IOException e) {
                               throw new RuntimeException(e);
                           }
                       });
                       thread.start();
                   }
                   if (!map.contains(oldPod.getMetadata().getName())) {
                       map.remove(Objects.requireNonNull(oldPod.getMetadata().getName()));
                   }
               }

               @Override
               public void onDelete(V1Pod pod, boolean deletedFinalStateUnknown) {
                   System.out.printf("%s pod deleted!\n", pod.getMetadata().getName());
               }
           });

        factory.startAllRegisteredInformers();
        Thread.sleep(1000 * 60 * 60 * 60);
        System.out.println("informer stopped..");
    }
}