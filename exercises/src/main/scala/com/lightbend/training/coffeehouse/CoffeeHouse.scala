package com.lightbend.training.coffeehouse

import java.util.concurrent.TimeUnit

import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  OneForOneStrategy,
  Props,
  SupervisorStrategy,
  Terminated
}

import scala.concurrent.duration._

object CoffeeHouse {
  case class CreateGuest(favoriteCoffee: Coffee, guestCaffeineLimit: Int)
  case class ApproveCoffee(coffee: Coffee, guest: ActorRef)

  def props(caffeineLimit: Int): Props = Props(new CoffeeHouse(caffeineLimit))
}

class CoffeeHouse(caffeineLimit: Int) extends Actor with ActorLogging {
  import CoffeeHouse._

  log.debug("CoffeeHouse open")

  override def supervisorStrategy(): SupervisorStrategy = {
    //decider is PF like = (Throwable => Directive(resume,restart,stop,escalate))
    //If we catch CaffeineException then we use our strategy in method OneForOneStrategy
    //and our strategy will be SupervisorStrategy.Stop
    //But if there will be another one exception we use default super.supervisorStrategy.decider
    val decider: SupervisorStrategy.Decider = {
      case Guest.CaffeineException => SupervisorStrategy.Stop
      case Waiter.FrustratedException(coffee, guest) =>
        barista.forward(Barista.PrepareCoffee(coffee, guest))
        SupervisorStrategy.Restart
    }
    OneForOneStrategy()(decider.orElse(super.supervisorStrategy.decider))
  }

  private var guestBook: Map[ActorRef, Int] = Map.empty.withDefaultValue(0)

  private val baristaAccuracy =
    context.system.settings.config.getInt("coffee-house.barista.accuracy")

  private val waiterMaxComplaintCount =
    context.system.settings.config
      .getInt("coffee-house.waiter.max-complaint-count")

  private val finishCoffeeDuration: FiniteDuration = {
    context.system.settings.config
      .getDuration(
        "coffee-house.guest.finish-coffee-duration",
        TimeUnit.MILLISECONDS
      )
      .millis
  }

  private val prepareCoffeeDuration: FiniteDuration = {
    context.system.settings.config
      .getDuration(
        "coffee-house.barista.prepare-coffee-duration",
        TimeUnit.MICROSECONDS
      )
      .millis
  }

  private val barista: ActorRef = createBarista()
  private val waiter: ActorRef = createWaiter()

  protected def createBarista(): ActorRef =
    context.actorOf(
      Barista.props(prepareCoffeeDuration, baristaAccuracy),
      "barista"
    )
  protected def createGuest(favoriteCoffee: Coffee,
                            guestCaffeineLimit: Int): ActorRef =
    context.actorOf(
      Guest
        .props(waiter, favoriteCoffee, finishCoffeeDuration, guestCaffeineLimit)
    )
  protected def createWaiter(): ActorRef =
    context.actorOf(
      Waiter.props(self, barista, waiterMaxComplaintCount),
      "waiter"
    )

  override def receive: Receive = {
    case CreateGuest(favoriteCoffee, guestCaffeineLimit) =>
      val guest = createGuest(favoriteCoffee, guestCaffeineLimit)
      guestBook += guest -> 0
      log.info(s"Guest $guest added to guest book")
      context.watch(guest)
    case ApproveCoffee(coffee, guest) if guestBook(guest) < caffeineLimit =>
      guestBook += guest -> (guestBook(guest) + 1)
      log.info(s"Guest $guest caffeine count incremented")
      barista.forward(Barista.PrepareCoffee(coffee, guest))
    case ApproveCoffee(coffee, guest) =>
      log.info(s"Sorry $guest but you have reached your limit")
      context.stop(guest)
    case Terminated(guest) =>
      log.info(s"Thx $guest for being our guest!")
      guestBook -= guest
  }
}
