
package agentSystem;
import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.typesafe.config.Config;

import agentSystem.ResponsibilityRequest;
import scala.concurrent.Future;
import akka.util.Timeout;
import static akka.pattern.Patterns.ask;
import agentSystem.InternalAgent;
import types.OmiTypes.WriteRequest;

public abstract class JavaInternalAgent extends UntypedActor implements InternalAgent {
  /**
   *  static public Props props(final Config _config)
   *  THIS STATIC METHOD MUST EXISTS FOR JavaInternalAgent. 
   *  WITHOUT IT JavaInternalAgent CAN NOT BE INITIALIZED.
   *  Implement it in way that
   *  <a href="http://doc.akka.io/docs/akka/current/java/untyped-actors.html#Recommended_Practices">Akka recommends to</a>.
   *
   *  @param _config Contains configuration for this agent, as given in application.conf.
   */
  // TODO: static method cannot be defined in the interface?

  protected LoggingAdapter log = Logging.getLogger(getContext().system(), this);
  protected Config config;
  protected ActorRef agentSystem = context().parent();
  protected String name = self().path().name();
  @Override
  public InternalAgentResponse restart()throws StartFailed, CommandFailed {
    stop();
    return start();
  }

  final public Future<ResponsibleAgentResponse> writeToNode( WriteRequest write, Timeout timeout ){
    ResponsibilityRequest rw = new ResponsibilityRequest(name, write);
    Future<Object> future = ask( agentSystem,rw, timeout);
    Future<ResponsibleAgentResponse> result = types.OmiTypes.JavaHelpers.formatWriteFuture(future);
    return result;
  }
}