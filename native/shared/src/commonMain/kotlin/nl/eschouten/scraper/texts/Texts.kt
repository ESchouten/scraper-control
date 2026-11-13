// Every word the apps show, in Dutch and English: ONE source for Compose (Android, desktop)
// and SwiftUI (iOS), so both apps always say the same.
// Each text is t(nl, en): a missing translation does not compile.
// The apps follow the phone's language (Dutch phone: Dutch, anything else: English) unless
// a language is chosen in Settings. Use: tr.cancel, tr.routeStarted(title).
// Swift: TextsKt.tr.cancel (or the `tr` helper in the iOS app).
package nl.eschouten.scraper.texts

val appLanguages = listOf("nl", "en")

/** The current texts; change with [useLanguage]. */
var tr: Tr = Tr(phoneLanguage())
  private set

/** "" = follow the phone, or "nl" / "en". */
fun useLanguage(choice: String) {
  tr = Tr(if (choice in appLanguages) choice else phoneLanguage())
}

/** "nl" on a Dutch phone, otherwise "en". */
fun phoneLanguage(): String = if (systemLanguage() == "nl") "nl" else "en"

/** The system's language code ("nl", "en", "de", ...), per platform. */
internal expect fun systemLanguage(): String

class Tr(val code: String) {
  private val isEn get() = code == "en"
  private fun t(nl: String, en: String) = if (isEn) en else nl

  // ---- common -------------------------------------------------------------------
  val cancel: String get() = t("Annuleren", "Cancel")
  val save: String get() = t("Opslaan", "Save")
  val close: String get() = t("Sluiten", "Close")
  val done: String get() = t("Klaar", "Done")
  val back: String get() = t("Terug", "Back")
  val next: String get() = t("Verder", "Next")
  val skip: String get() = t("Overslaan", "Skip")
  val hide: String get() = t("Verbergen", "Hide")
  val show: String get() = t("Tonen", "Show")
  val pin: String get() = t("Pincode", "PIN")
  val settings: String get() = t("Instellingen", "Settings")
  val robots: String get() = t("Robots", "Robots")
  val robot: String get() = t("Robot", "Robot")
  val routes: String get() = t("Routes", "Routes")
  val name: String get() = t("Naam", "Name")
  val details: String get() = t("Details", "Details")
  val bluetooth: String get() = "Bluetooth"
  val on: String get() = t("Aan", "On")
  val off: String get() = t("Uit", "Off")
  val paused: String get() = t("Pauze", "Paused")
  val busy: String get() = t("Bezig", "Busy")
  val busyDots: String get() = t("Bezig...", "Busy...")
  val connecting: String get() = t("Verbinden", "Connecting")
  val connected: String get() = t("Verbonden", "Connected")
  val notConnected: String get() = t("Niet verbonden", "Not connected")
  val notConnectedYet: String get() = t("Nog niet verbonden", "Not connected yet")
  val searchRobot: String get() = t("Robot zoeken", "Find robot")
  val addRobot: String get() = t("Robot toevoegen", "Add robot")
  val testConnection: String get() = t("Verbinding testen", "Test connection")
  val started: String get() = t("Gestart", "Started")
  val failed: String get() = t("Mislukt", "Failed")
  val dryRun: String get() = t("Proefrun", "Dry run")
  val planning: String get() = t("Tijdsplanning", "Schedule")
  val planningShort: String get() = t("Planning", "Schedule")
  val nextRide: String get() = t("Volgende rit", "Next run")
  val battery: String get() = t("Accu", "Battery")
  val exampleBarn: String get() = t("Bijv. Stal 2", "E.g. Barn 2")
  fun routeLetter(r: String): String = "Route $r"
  // ---- dates ----------------------------------------------------------------------
  val months: List<String> get() = if (isEn)
      listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    else
      listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
  val today: String get() = t("vandaag", "today")
  val yesterday: String get() = t("gisteren", "yesterday")
  val justNow: String get() = t("zojuist", "just now")
  fun minutesAgo(n: Int): String = t("$n min geleden", "$n min ago")
  fun hoursAgo(n: Int): String = t("$n uur geleden", if (n == 1) "1 hour ago" else "$n hours ago")
  val decimal: String get() = t(",", ".")
  // ---- controller / outcomes ------------------------------------------------------
  fun dryRunDone(title: String): String = t(
    "Proefrun $title klaar. Niets gestart, alles staat weer zoals het was.",
    "Dry run $title done. Nothing started, everything is back as it was.",
  )
  fun routeStartedResumes(title: String): String = t(
    "$title gestart. De tijdsplanning gaat na de route weer aan.",
    "$title started. The schedule comes back on after the route.",
  )
  fun routeStartedDot(title: String): String = t("$title gestart.", "$title started.")
  fun routeStarted(title: String): String = t("$title gestart", "$title started")
  val routeStoppedTitle: String get() = t("Route gestopt", "Route stopped")
  val routeStoppedMsg: String get() = t(
    "De robot reed een route en is gestopt (Start/Stop, zoals op het paneel). Kijk even bij de robot.",
    "The robot was driving a route and has stopped (Start/Stop, as on the panel). Please check the robot.",
  )
  val stoppedShort: String get() = t("Gestopt", "Stopped")
  // After a stop the robot asks whether to finish the route (Erik 2026-11-11).
  val finishRouteQ: String get() = t("Route afmaken?", "Finish the route?")
  val finishRouteBody: String get() = t(
    "De robot is gestopt. Afmaken: hij rijdt de route verder. Niet afmaken: de route stopt en de tijdsplanning blijft uit.",
    "The robot has stopped. Finish: it drives the rest of the route. Don't finish: the route ends and the schedule stays off.",
  )
  val finishRoute: String get() = t("Afmaken", "Finish")
  val dontFinishRoute: String get() = t("Niet afmaken", "Don't finish")
  val later: String get() = t("Later", "Later")
  val routeContinuesMsg: String get() = t("De robot rijdt de route verder.", "The robot drives on with the route.")
  val routeCancelledMsg: String get() = t(
    "Route afgebroken. De tijdsplanning blijft uit.",
    "Route cancelled. The schedule stays off.",
  )
  val finishLeftOpenMsg: String get() = t(
    "De robot is gestopt. De vraag of hij de route moet afmaken staat nog op de robot: beantwoord hem daar, of tik weer op een route.",
    "The robot has stopped. The question whether to finish the route is still on the robot: answer it there, or tap a route again.",
  )
  fun planningIs(on: Boolean): String =
      if (on) t("Tijdsplanning staat AAN", "Schedule is ON") else t("Tijdsplanning staat UIT", "Schedule is OFF")
  val planningChangeFailed: String get() = t("Tijdsplanning wijzigen mislukt", "Changing the schedule failed")
  val statusUpdated: String get() = t("Status bijgewerkt", "Status updated")
  val noRobotNearby: String get() = t("Geen robot gevonden in de buurt.", "No robot found nearby.")
  val noConnection: String get() = t("Geen verbinding met de robot.", "No connection with the robot.")
  // ---- home-screen widget -------------------------------------------------------------
  fun widgetConnecting(title: String): String = t("$title: verbinden...", "$title: connecting...")
  val bluetoothOffShort: String get() = t("Bluetooth uit", "Bluetooth off")
  val tapRouteToStart: String get() = t("Tik op een route om te starten", "Tap a route to start it")
  val tapToStart: String get() = t("Tik om te starten", "Tap to start")
  fun widgetSchedule(on: Boolean, next: String): String = when {
    !on -> t("Planning uit", "Schedule off")
    next.isEmpty() -> t("Planning aan", "Schedule on")
    else -> t("Planning aan · volgende $next", "Schedule on · next $next")
  }
  fun widgetSwitchPlanning(on: Boolean): String =
      if (on) t("Planning aanzetten", "Switch the schedule on") else t("Planning uitzetten", "Switch the schedule off")
  val setupFirst: String get() = t("Stel eerst de app in", "Set up the app first")
  val setupFirstBody: String get() = t(
    "Open Scraper Control en kies je robot. Daarna kun je de widget plaatsen.",
    "Open Scraper Control and choose your robot. Then you can place the widget.",
  )
  val whichRoute: String get() = t("Welke route?", "Which route?")
  val whichRobot: String get() = t("Welke robot?", "Which robot?")
  val widgetBarName: String get() = "Routes A-D"
  val widgetBarDescription: String get() = t("Alle routes van een robot, één tik per route.", "All routes of a robot, one tap per route.")
  val widgetTileName: String get() = t("Eén route", "One route")
  val widgetTileDescription: String get() = t("Eén route als tegel: tik om te starten.", "One route as a tile: tap to start.")
  fun widgetFailedTitle(title: String): String = t("$title niet gestart", "$title not started")
  val widgetTimedOut: String get() = t(
    "De robot gaf niet op tijd antwoord. Kijk in de app of de route loopt.",
    "The robot did not answer in time. Check in the app whether the route runs.",
  )
  val widgetFailuresChannel: String get() = t("Mislukte widget-starts", "Failed widget starts")
  // ---- connecting over Bluetooth ----------------------------------------------------
  val btPinHelp: String get() = t(
    "De pincode van de robot, dezelfde als in de app van de fabrikant. Leeg laten als je robot er geen heeft.",
    "The robot's PIN, the same as in the manufacturer's app. Leave empty if your robot has none.",
  )
  val btReachHelp: String get() = t(
    "Sta in de buurt van de robot, zet Bluetooth aan en sluit andere apps die met de robot verbinden. De robot accepteert één verbinding tegelijk.",
    "Stand near the robot, switch Bluetooth on and close other apps that connect to the robot. The robot accepts one connection at a time.",
  )
  val btScanHelp: String get() = t(
    "Ben je in de buurt van de robot? De robot accepteert één verbinding tegelijk: sluit andere apps die met de robot verbinden.",
    "Are you near the robot? The robot accepts one connection at a time: close other apps that connect to the robot.",
  )
  val stepLinked: String get() = t("Bluetooth verbonden", "Bluetooth connected")
  val stepGranted: String get() = t("Pincode geaccepteerd", "PIN accepted")
  val stepScreenReady: String get() = t("Gesynchroniseerd met de robot", "Synced with the robot")
  val stepPlanningOff: String get() = t("Tijdsplanning tijdelijk uit", "Schedule paused")
  val stepPlanningOn: String get() = t("Tijdsplanning weer aan", "Schedule back on")
  val stepMenu: String get() = t("Routemenu geopend", "Route menu opened")
  fun stepSelected(title: String): String = t("$title gekozen", "$title chosen")
  val stepStartPressed: String get() = t("Start ingedrukt", "Start pressed")
  val stepAnswered: String get() = t("Vraag over het tijdpad beantwoord", "Schedule question answered")
  val stepRestored: String get() = t("Alles teruggezet", "Everything put back")
  val nextCheckPin: String get() = t("Pincode controleren", "Checking PIN")
  val nextWaitPanel: String get() = t("Synchroniseren met de robot (eerste keer ca. 1,5 min)", "Syncing with the robot (first time about 1.5 min)")
  fun syncing(percent: Int): String = t("Synchroniseren met de robot ($percent%)", "Syncing with the robot ($percent%)")
  val nextReadStatus: String get() = t("Status lezen", "Reading status")
  val nextPlanningOff: String get() = t("Tijdsplanning uitzetten", "Pausing schedule")
  val nextOpenMenu: String get() = t("Routemenu openen", "Opening route menu")
  val nextRestore: String get() = t("Terugzetten", "Putting back")
  val nextChooseRoute: String get() = t("Route kiezen", "Choosing route")
  val nextPressStart: String get() = t("Start indrukken", "Pressing Start")
  val nextAnswer: String get() = t("Vraag beantwoorden", "Answering question")
  val nextFinish: String get() = t("Afronden", "Finishing")
  val panelTitle: String get() = t("Bedieningspaneel", "Control panel")
  val panelSubtitle: String get() = t("Het display en de knoppen van de robot", "The robot's display and buttons")
  val btOff: String get() = t("Bluetooth staat uit.", "Bluetooth is off.")
  val btDenied: String get() = t("De app mag Bluetooth niet gebruiken.", "The app may not use Bluetooth.")
  val btUnsupported: String get() = t("Dit apparaat heeft geen Bluetooth.", "This device has no Bluetooth.")
  val pinRefused: String get() = t("De robot weigert de pincode.", "The robot rejects the PIN.")
  val robotBusyNothingPressed: String get() = t(
    "De robot is bezig of staat niet op het beginscherm. Er is niets ingedrukt.",
    "The robot is busy or not on its home screen. Nothing was pressed.",
  )
  val noQuestionAfterStart: String get() = t(
    "Start is ingedrukt, maar de vraag over het tijdpad kwam niet. Kijk even bij de robot.",
    "Start was pressed, but the schedule question did not come. Please check the robot.",
  )
  val unknownError: String get() = t("Onbekende fout", "Unknown error")
  // ---- panel screen -----------------------------------------------------------------
  val panel: String get() = t("Paneel", "Panel")
  val liveDisconnect: String get() = t("Live · verbreken", "Live · disconnect")
  val connectingDots: String get() = t("Verbinden...", "Connecting...")
  val connectPanel: String get() = t("Verbinden met het paneel", "Connect to the panel")
  val panelInfo: String get() = t(
    "De knoppen staan zoals op het paneel van de robot en werken direct. Zolang je hier verbonden bent, kan geen andere app verbinden.",
    "The buttons are laid out as on the robot's panel and act at once. While you are connected here, no other app can connect.",
  )
  val softLeft: String get() = t("Softkey links", "Soft key left")
  val softMiddle: String get() = t("Softkey midden", "Soft key middle")
  val softRight: String get() = t("Softkey rechts", "Soft key right")
  val keyUp: String get() = t("Omhoog", "Up")
  val keyDown: String get() = t("Omlaag", "Down")
  // ---- scan view ------------------------------------------------------------------
  fun scanFailed(e: Any): String =
      t("Zoeken lukt niet. Staat Bluetooth aan? ($e)", "Search failed. Is Bluetooth on? ($e)")
  val scanning: String get() = t("Zoeken naar je robot...", "Looking for your robot...")
  val noRobotFound: String get() = t("Geen robot gevonden", "No robot found")
  fun robotsFound(n: Int): String = if (n == 1) t("Robot gevonden", "Robot found") else t("$n robots gevonden", "$n robots found")
  fun signal(bars: String): String = t("Signaal $bars", "Signal $bars")
  fun signalWithId(id: String, bars: String): String = t("$id · signaal $bars", "$id · signal $bars")
  val iphoneNameHelp: String get() =
      t(" Op iPhone helpt het om de naam van de robot in te vullen.", " On iPhone, filling in the robot's name helps.")
  val robotNameOptional: String get() = t("Naam van de robot (optioneel)", "Robot's name (optional)")
  val searchAgain: String get() = t("Opnieuw zoeken", "Search again")
  val signalStrong: String get() = t("sterk", "strong")
  val signalGood: String get() = t("goed", "good")
  val signalWeak: String get() = t("zwak", "weak")
  val signalVeryWeak: String get() = t("heel zwak", "very weak")
  // ---- dialogs ------------------------------------------------------------------------
  fun routeNameTitle(r: String): String = t("Naam voor route $r", "Name for route $r")
  val routeNameHint: String get() = t("Bijv. Voergang of Boxen links", "E.g. Feed alley or Left stalls")
  // ---- robot switcher ---------------------------------------------------------------
  val noStatusYet: String get() = t("Nog geen status", "No status yet")
  val planningOnLower: String get() = t("planning aan", "schedule on")
  val planningOffLower: String get() = t("planning uit", "schedule off")
  fun nextRideLower(t: String): String = t("volgende rit $t", "next run $t")
  // ---- run progress -----------------------------------------------------------------
  fun stoppedAt(step: String): String = t("Gestopt bij: $step", "Stopped at: $step")
  // ---- logbook ------------------------------------------------------------------------
  val logbook: String get() = t("Logboek", "Log")
  val clearLogbook: String get() = t("Logboek wissen", "Clear log")
  val clearLogbookQ: String get() = t("Logboek wissen?", "Clear the log?")
  val clearLogbookBody: String get() = t(
    "Alle regels worden verwijderd. De robot merkt hier niets van.",
    "All entries are removed. The robot is not affected.",
  )
  val clear: String get() = t("Wissen", "Clear")
  val logbookEmpty: String get() = t("Nog niets in het logboek", "Nothing in the log yet")
  val logbookEmptyHelp: String get() = t(
    "Gestarte routes, proefruns en fouten komen hier, ook die via de widget.",
    "Started routes, dry runs and errors show up here, also those from the widget.",
  )
  val viaWidget: String get() = t("via widget", "via widget")
  // ---- control screen ---------------------------------------------------------------
  val manualRoutes: String get() = t("Handmatige routes", "Manual routes")
  val edit: String get() = t("Aanpassen", "Edit")
  val allRoutesHidden: String get() = t(
    "Alle routes zijn verborgen. Tik op Aanpassen om ze weer te tonen.",
    "All routes are hidden. Tap Edit to show them again.",
  )
  val otherRobot: String get() = t("Andere robot kiezen", "Choose another robot")
  fun robotCount(n: Int): String = t("$n robots · tik op de naam om te wisselen", "$n robots · tap the name to switch")
  fun wasBusy(ago: String): String = t("Was bezig · $ago", "Was busy · $ago")
  fun updated(ago: String): String = t("Bijgewerkt $ago", "Updated $ago")
  val planningOffQ: String get() = t("Tijdsplanning uitzetten?", "Switch the schedule off?")
  val planningOnQ: String get() = t("Tijdsplanning aanzetten?", "Switch the schedule on?")
  val planningOffBody: String get() = t(
    "De robot rijdt dan geen geplande routes meer, tot je de planning weer aanzet.",
    "The robot then drives no scheduled routes until you switch the schedule on again.",
  )
  val planningOnBody: String get() =
      t("De robot gaat weer volgens zijn planning rijden.", "The robot drives according to its schedule again.")
  val switchOff: String get() = t("Uitzetten", "Switch off")
  val switchOn: String get() = t("Aanzetten", "Switch on")
  val notStartedYet: String get() = t("Nog niet gestart met de app", "Not started with the app yet")
  fun lastStarted(time: String): String = t("Laatst gestart $time", "Last started $time")
  fun startRoute(title: String): String = t("Start $title", "Start $title")
  fun widgetTipIos(app: String): String = t(
    "Zet de routes op je beginscherm: houd het scherm ingedrukt, tik op + en zoek $app.",
    "Put the routes on your home screen: press and hold the screen, tap + and search for $app.",
  )
  fun widgetTipAndroid(app: String): String = t(
    "Zet de routes op je beginscherm: houd het scherm ingedrukt, kies Widgets en zoek $app.",
    "Put the routes on your home screen: press and hold the screen, choose Widgets and search for $app.",
  )
  // ---- add robot ----------------------------------------------------------------------
  fun robotAdded(name: String): String = t("$name is toegevoegd", "$name was added")
  val addRobotHelp: String get() = t(
    "Ga bij de robot staan die je wilt toevoegen. Robots die je al hebt, staan niet in de lijst.",
    "Stand near the robot you want to add. Robots you already have are not in the list.",
  )
  val robotType: String get() = t("Type robot", "Robot type")
  val details2: String get() = t("Gegevens", "Details")
  val nameInApp: String get() = t("Naam in de app", "Name in the app")
  val add: String get() = t("Toevoegen", "Add")
  // ---- run sheet ----------------------------------------------------------------------
  fun dryRunDoneShort(title: String): String = t("Proefrun $title klaar", "Dry run $title done")
  val dryRunNoDrive: String get() = t("Proefrun · de robot rijdt niet", "Dry run · the robot does not drive")
  val manualRoute: String get() = t("Handmatige route", "Manual route")
  fun manualRouteLetter(r: String): String = t("Handmatige route $r", "Manual route $r")
  val sheetPlanningOn: String get() = t(
    "De tijdsplanning staat aan. Die gaat tijdelijk uit en na de route vanzelf weer aan.",
    "The schedule is on. It is paused and comes back on by itself after the route.",
  )
  val sheetPlanningOff: String get() = t("De tijdsplanning staat uit en blijft uit.", "The schedule is off and stays off.")
  val sheetPlanningUnknown: String get() = t(
    "De tijdsplanning wordt eerst gecontroleerd. Na de route staat die weer zoals hij stond.",
    "The schedule is checked first. After the route it is back as it was.",
  )
  val sheetSafe: String get() = t(
    "Rijdt de robot al een route, dan stopt hij die. Staat hij in een ander menu, dan wordt er niets ingedrukt.",
    "If the robot is already driving a route, it stops it. If it is in another menu, nothing is pressed.",
  )
  val slideToStart: String get() = t("Schuif om te starten", "Slide to start")
  val dryRunFirst: String get() = t("Eerst een proefrun, zonder te rijden", "First a dry run, without driving")
  val keepAppOpen: String get() = t("Houd de app open tot dit klaar is.", "Keep the app open until this is done.")
  val dryRunOk: String get() = t("Proefrun geslaagd", "Dry run succeeded")
  val routeStartedTitle: String get() = t("Route gestart", "Route started")
  val dryRunOkBody: String get() = t(
    "Alles werkte tot aan Start. Er is niets gestart en alles staat weer zoals het was.",
    "Everything worked up to Start. Nothing was started and everything is back as it was.",
  )
  val drivingResumes: String get() = t(
    "De robot rijdt nu. De tijdsplanning gaat na de route vanzelf weer aan.",
    "The robot is driving now. The schedule comes back on by itself after the route.",
  )
  val drivingStaysOff: String get() =
      t("De robot rijdt nu. De tijdsplanning blijft uit.", "The robot is driving now. The schedule stays off.")
  val startForReal: String get() = t("Nu echt starten", "Start for real")
  val btOnHelp: String get() =
      t("Zet Bluetooth aan op je telefoon en probeer het opnieuw.", "Switch Bluetooth on on your phone and try again.")
  fun btPermissionHelp(app: String): String = t(
    "Geef $app toestemming voor Bluetooth (Apparaten in de buurt) in de instellingen van je telefoon.",
    "Give $app permission for Bluetooth (Nearby devices) in your phone's settings.",
  )
  val btDeviceHelp: String get() =
      t("Gebruik een telefoon, tablet of computer met Bluetooth.", "Use a phone, tablet or computer with Bluetooth.")
  val robotUnreachable: String get() = t("Robot niet bereikbaar", "Robot not reachable")
  val pinWrong: String get() = t("Pincode klopt niet", "Wrong PIN")
  val robotBusy: String get() = t("Robot is bezig", "Robot is busy")
  val robotBusyHelp: String get() = t(
    "Er is niets ingedrukt. Probeer het opnieuw als de robot klaar is en op het beginscherm staat.",
    "Nothing was pressed. Try again when the robot is done and on its home screen.",
  )
  val checkRobot: String get() = t("Kijk even bij de robot", "Please check the robot")
  val startFailed: String get() = t("Starten lukte niet", "Starting did not work")
  val changePin: String get() = t("Pincode wijzigen", "Change PIN")
  val tryAgain: String get() = t("Opnieuw proberen", "Try again")
  // ---- onboarding ---------------------------------------------------------------------
  val welcomeTitle: String get() = t("Je mestrobot,\nmet één tik.", "Your manure robot,\nwith one tap.")
  fun welcomeLead(app: String, models: String): String = t(
    "$app start de handmatige routes van je mestrobot via Bluetooth. Werkt nu met de $models.",
    "$app starts your manure robot's manual routes over Bluetooth. Works with the $models for now.",
  )
  val featureTapTitle: String get() = t("Eén tik per route", "One tap per route")
  val featureTapBody: String get() =
      t("In de app of met een widget op je beginscherm.", "In the app or with a widget on your home screen.")
  val featurePlanningTitle: String get() = t("Planning klopt altijd", "Schedule always right")
  val featurePlanningBody: String get() = t(
    "Staat de tijdsplanning aan, dan gaat die tijdelijk uit en na de route weer aan.",
    "If the schedule is on, it is paused and comes back on after the route.",
  )
  val featureSafeTitle: String get() = t("Veilig", "Safe")
  val featureSafeBody: String get() = t(
    "Tik tijdens een rit nog een keer op een route, dan stopt de robot. Met een proefrun test je alles behalve Start.",
    "Tap a route again during a run and the robot stops. A dry run tests everything except Start.",
  )
  val getStarted: String get() = t("Aan de slag", "Get started")
  fun stepOf(i: Int, n: Int): String = t("Stap $i van $n", "Step $i of $n")
  val findLead: String get() =
      t("Ga in de buurt van de robot staan en zet Bluetooth aan.", "Stand near the robot and switch Bluetooth on.")
  val doneToApp: String get() = t("Klaar, naar de app", "Done, go to the app")
  val testLater: String get() = t("Later testen", "Test later")
  fun connectedWith(name: String): String = if (name.isEmpty())
      t("Verbonden met de robot", "Connected to the robot")
    else
      t("Verbonden met $name", "Connected to $name")
  val thatFailed: String get() = t("Dat lukte niet", "That did not work")
  val readyPlanningOn: String get() =
      t("De tijdsplanning staat aan. Alles is klaar voor gebruik.", "The schedule is on. Everything is ready to use.")
  val readyPlanningOff: String get() =
      t("De tijdsplanning staat uit. Alles is klaar voor gebruik.", "The schedule is off. Everything is ready to use.")
  val ready: String get() = t("Alles is klaar voor gebruik.", "Everything is ready to use.")
  fun pinRejectedHelp(help: String): String = t("De robot weigert deze pincode. $help", "The robot rejects this PIN. $help")
  // ---- settings screen --------------------------------------------------------------
  val inUse: String get() = t("In gebruik", "In use")
  val notLinkedYet: String get() = t("Nog niet gekoppeld", "Not linked yet")
  val secondRobot: String get() = t("Voor een tweede stal of robot", "For a second barn or robot")
  val activity: String get() = t("Activiteit", "Activity")
  val nothingStarted: String get() = t("Nog niets gestart", "Nothing started yet")
  fun lastEvent(title: String, time: String): String = t("Laatst: $title, $time", "Last: $title, $time")
  val widgets: String get() = "Widgets"
  val routesOnHome: String get() = t("Routes op je beginscherm", "Routes on your home screen")
  fun widgetHelpAndroid(app: String): String = t(
    "Houd je beginscherm ingedrukt, kies Widgets en zoek $app. \"Eén route\" is een tegel per route, \"Routes A-D\" heeft alle knoppen van een robot. Bij het plaatsen kies je robot en route.",
    "Press and hold your home screen, choose Widgets and search for $app. \"One route\" is a tile per route, \"Routes A-D\" has all buttons of a robot. When placing it you choose robot and route.",
  )
  fun widgetHelpIos(app: String): String = t(
    "Houd je beginscherm ingedrukt, tik op + en zoek $app (iOS 17 of nieuwer). Kies daarna via \"Wijzig widget\" de robot en de route.",
    "Press and hold your home screen, tap + and search for $app (iOS 17 or later). Then choose the robot and route with \"Edit widget\".",
  )
  val about: String get() = t("Over", "About")
  val disclaimer: String get() = t(
    "Onofficiële app: niet gemaakt door, verbonden aan of ondersteund door de makers van de robots die hij bedient. Gebruik op eigen risico en houd zicht op de robot.",
    "Unofficial app: not made, endorsed or supported by the makers of the robots it controls. Use at your own risk and keep the robot in sight.",
  )
  val resetSetup: String get() = t("Opnieuw instellen", "Set up again")
  val resetSetupSub: String get() = t("Doorloop de eerste stappen opnieuw", "Go through the first steps again")
  val resetSetupQ: String get() = t("Opnieuw instellen?", "Set up again?")
  val resetSetupBody: String get() = t(
    "Je gaat terug naar het welkomstscherm. Je robots, routenamen en het logboek blijven bewaard.",
    "You go back to the welcome screen. Your robots, route names and the log are kept.",
  )
  val language: String get() = t("Taal", "Language")
  val languagePhone: String get() = t("Zoals de telefoon", "As the phone")
  val robotNameTitle: String get() = t("Naam van de robot", "Robot's name")
  val searchedAutomatically: String get() = t("Wordt automatisch gezocht", "Found automatically")
  val noPin: String get() = t("Geen pincode", "No PIN")
  val testConnectionSub: String get() = t("Verbinden, status lezen en weer los", "Connect, read status and disconnect")
  val makeActive: String get() = t("Maakt deze robot de robot in gebruik", "Makes this robot the one in use")
  val connectionWorks: String get() = t("De verbinding werkt", "The connection works")
  fun version(name: String): String = t("Versie $name", "Version $name")   // 1.6.120: the last number is the build
  val readRoutes: String get() = t("Routes uitlezen", "Read routes")
  val readRoutesSub: String get() = t("Lees welke deelroutes onder A t/m D zitten", "Read which sub-routes make up A to D")
  val routesRead: String get() = t("Routes gelezen", "Routes read")
  fun routeParts(p: String): String = t("deelroutes $p", "sub-routes $p")
  val routeEmpty: String get() = t("leeg op de robot", "empty on the robot")
  val notSupported: String get() = t("Kan deze robot niet", "This robot cannot do that")
  val tapToName: String get() = t("Tik om een naam te geven", "Tap to give it a name")
  fun routeTapToChange(r: String): String = t("Route $r · tik om te wijzigen", "Route $r · tap to change")
  val routesHelp: String get() = t(
    "De routes komen uit het menu HANDM. ROUTE van deze robot. Wat een route rijdt, stel je in op de robot zelf.",
    "The routes come from this robot's MANUAL ROUTE menu. What a route drives is set on the robot itself.",
  )
  fun removeRobotQ(name: String): String = t("$name verwijderen?", "Remove $name?")
  val removeRobotBody: String get() = t(
    "De robot verdwijnt uit de app en uit je widgets. Op de robot zelf verandert niets.",
    "The robot disappears from the app and your widgets. Nothing changes on the robot itself.",
  )
  val remove: String get() = t("Verwijderen", "Remove")
  val removeRobot: String get() = t("Robot verwijderen", "Remove robot")
}
