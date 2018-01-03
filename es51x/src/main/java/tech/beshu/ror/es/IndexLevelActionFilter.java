/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.es;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.shims.es.ACLHandler;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<ACL>> acl;
  private final AtomicReference<ESContext> context = new AtomicReference<>();
  private final Environment env;
  private final IndexNameExpressionResolver indexResolver;


  @Inject
  public IndexLevelActionFilter(Settings settings,
                                ClusterService clusterService,
                                NodeClient client,
                                ThreadPool threadPool,
                                SettingsObservableImpl settingsObservable
  ) {
    super(settings);
    LoggerShim loggerShim = ESContextImpl.mkLoggerShim(logger);

    indexResolver = new IndexNameExpressionResolver(settings);
    this.env = new Environment(settings);
    BasicSettings baseSettings = BasicSettings.fromFile(loggerShim, env.configFile().toAbsolutePath(), settings.getAsStructuredMap());

    this.context.set(new ESContextImpl(client, baseSettings));

    this.clusterService = clusterService;
    this.threadPool = threadPool;
    this.acl = new AtomicReference<>(Optional.empty());


    settingsObservable.addObserver((o, arg) -> {
      logger.info("Settings observer refreshing...");
      RawSettings newRaw = new RawSettings(settingsObservable.getCurrent().asMap());
      Environment newEnv = new Environment(settings);
      BasicSettings newBaseSettings = new BasicSettings(newRaw, newEnv.configFile().toAbsolutePath());
      ESContext newContext = new ESContextImpl(client, newBaseSettings);
      this.context.set(newContext);

      if (newContext.getSettings().isEnabled()) {
        try {
          ACL newAcl = new ACL(newContext);
          acl.set(Optional.of(newAcl));
          logger.info("Configuration reloaded - ReadonlyREST enabled");
        } catch (Exception ex) {
          logger.error("Cannot configure ReadonlyREST plugin", ex);
          throw ex;
        }
      }
      else {
        acl.set(Optional.empty());
        logger.info("Configuration reloaded - ReadonlyREST disabled");
      }
    });

    settingsObservable.forceRefresh();
    logger.info("Readonly REST plugin was loaded...");

    settingsObservable.pollForIndex(context.get());

  }


  @Override
  public int order() {
    return 0;
  }

  @Override
  public <Request extends ActionRequest, Response extends ActionResponse> void apply(Task task,
                                                                                     String action,
                                                                                     Request request,
                                                                                     ActionListener<Response> listener,
                                                                                     ActionFilterChain<Request, Response> chain) {

    Optional<ACL> acl = this.acl.get();
    if (acl.isPresent()) {
      handleRequest(acl.get(), task, action, request, listener, chain);
    }
    else {
      chain.proceed(task, action, request, listener);
    }
  }

  @Override
  public <Response extends ActionResponse> void apply(String action, Response response, ActionListener<Response> listener, ActionFilterChain<?, Response> chain) {
    chain.proceed(action, response, listener);
  }

  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(ACL acl,
                                                                                              Task task,
                                                                                              String action,
                                                                                              Request request,
                                                                                              ActionListener<Response> listener,
                                                                                              ActionFilterChain<Request, Response> chain) {
    RestChannel channel = ThreadRepo.channel.get();
    if (channel != null) {
      ThreadRepo.channel.remove();
    }
    boolean chanNull = channel == null;
    boolean reqNull = channel == null ? true : channel.request() == null;
    if (ACL.shouldSkipACL(chanNull, reqNull)) {
      chain.proceed(task, action, request, listener);
      return;
    }
    RequestInfo requestInfo = new RequestInfo(channel, task.getId(), action, request, clusterService, threadPool, context.get(), indexResolver);
    acl.check(requestInfo, new ACLHandler() {
      @Override
      public void onForbidden() {
        ElasticsearchStatusException exc = new ElasticsearchStatusException(
          context.get().getSettings().getForbiddenMessage(),
          acl.doesRequirePassword() ? RestStatus.UNAUTHORIZED : RestStatus.FORBIDDEN
        );
        if (acl.doesRequirePassword()) {
          exc.addHeader("WWW-Authenticate", "Basic");
        }
        listener.onFailure(exc);
      }

      @Override
      public void onAllow(Object blockExitResult) {
        boolean hasProceeded = false;
        try {
          //         @SuppressWarnings("unchecked")
//          ActionListener<Response> aclActionListener = (ActionListener<Response>) new ACLActionListener(
//            request, (ActionListener<ActionResponse>) listener, rc, blockExitResult, context, acl
//          );
//          chain.proceed(task, action, request, aclActionListener);


          chain.proceed(task, action, request, listener);
          hasProceeded = true;
          return;
        } catch (Throwable e) {
          e.printStackTrace();
        }
        if (!hasProceeded) {
          chain.proceed(task, action, request, listener);
        }
      }

      @Override
      public boolean isNotFound(Throwable throwable) {
        return throwable.getCause() instanceof ResourceNotFoundException;
      }

      @Override
      public void onNotFound(Throwable throwable) {
        listener.onFailure((ResourceNotFoundException) throwable.getCause());
      }

      @Override
      public void onErrored(Throwable t) {
        listener.onFailure((Exception) t);
      }
    });

  }

}