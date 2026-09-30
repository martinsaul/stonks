package stonks.engine.world

import stonks.engine.company.Sector
import stonks.engine.core.Rng

/** Parody headline templates. `{name}` and `{ticker}` are filled in. */
object Headlines {
    private val events: Map<EventType, List<String>> = mapOf(
        EventType.ANALYST_UPGRADE to listOf(
            "Analyst who owns a yacht named after {name} upgrades {ticker} to 'Buy'",
            "{name} upgraded: 'We finally read the annual report,' says analyst",
            "Wall Street darling status restored for {name} after upgrade",
        ),
        EventType.ANALYST_DOWNGRADE to listOf(
            "Analyst downgrades {name}, cites 'vibes'",
            "{ticker} cut to 'Hold' by firm that was wrong last quarter too",
            "{name} downgraded as analyst discovers the balance sheet",
        ),
        EventType.PRODUCT_NEWS_GOOD to listOf(
            "{name} launches app update users describe as 'fine, actually'",
            "{name} signs mid-sized partnership, press release uses the word 'synergy' 14 times",
            "Early reviews of {name}'s new gadget are cautiously positive",
        ),
        EventType.PRODUCT_NEWS_BAD to listOf(
            "{name} recalls product over 'unexpected smoke'",
            "{name} app outage enters its second hour; CEO blames Mercury retrograde",
            "Customers report {name}'s new feature is just an ad",
        ),
        EventType.PRODUCT_HIT to listOf(
            "{name}'s new product sells out in minutes; resellers ecstatic",
            "{name} unveils blockbuster product, audience gives standing ovation to a slide deck",
            "Viral hit: everyone's grandparents now own a {name} device",
        ),
        EventType.PRODUCT_FLOP to listOf(
            "{name} flagship launch flops; demo unit catches fire on stage",
            "{name}'s much-hyped product sells 11 units, 9 to employees",
            "Critics call {name}'s new product 'a solution looking for a problem'",
        ),
        EventType.SCANDAL to listOf(
            "Whistleblower alleges {name} hid losses in a spreadsheet tab named 'misc'",
            "{name} executives accused of expensing a private island",
            "Regulators raid {name} HQ; shredders reportedly 'very warm'",
        ),
        EventType.REG_APPROVAL to listOf(
            "Regulators approve {name}'s flagship treatment; shares soar",
            "{name} wins approval after a decade of trials and several very long meetings",
            "Green light: {name} clears final regulatory hurdle",
        ),
        EventType.REG_REJECTION to listOf(
            "Regulators reject {name}'s application, request 'actual data'",
            "{name} suffers regulatory setback; lead product sent back to the lab",
            "Rejected: {name} told to 'try again, but with science'",
        ),
        EventType.CEO_EXIT to listOf(
            "{name} CEO departs 'to spend more time with his offshore accounts'",
            "{name} CEO resigns abruptly; interim chief is the office plant",
            "Leadership shake-up at {name} as chief executive exits",
        ),
        EventType.STAR_CEO to listOf(
            "{name} poaches star CEO, promises 'a vision' and several pivots",
            "Celebrated turnaround artist named {name} CEO",
            "{name} hires legendary exec; LinkedIn engagement explodes",
        ),
        EventType.LAWSUIT_WON to listOf(
            "{name} wins landmark lawsuit; lawyers celebrate billable hours",
            "Court sides with {name} in patent fight over rounded corners",
            "{name} cleared in long-running legal battle",
        ),
        EventType.LAWSUIT_LOST to listOf(
            "{name} loses lawsuit, ordered to pay damages 'with a straight face'",
            "Jury finds against {name} in class action",
            "{name} hit with hefty fine after losing court case",
        ),
        EventType.SECONDARY_OFFERING to listOf(
            "{name} sells new shares to raise cash; existing holders feel diluted",
            "{name} announces stock offering, calls it 'an opportunity for you'",
        ),
        EventType.BUYBACK to listOf(
            "{name} announces share buyback, discovers it likes its own stock",
            "{name} board approves buyback program",
        ),
    )

    private val rumors: Map<EventType, List<String>> = mapOf(
        EventType.SCANDAL to listOf("Sources: regulators circling {name} over accounting questions"),
        EventType.REG_APPROVAL to listOf("Sources: {name} approval decision could come within days"),
        EventType.REG_REJECTION to listOf("Sources: regulators skeptical of {name}'s application"),
        EventType.PRODUCT_HIT to listOf("Leaked: {name} preparing a 'category-defining' launch"),
        EventType.PRODUCT_FLOP to listOf("Insiders whisper {name}'s next launch is in trouble"),
        EventType.CEO_EXIT to listOf("Sources: {name} CEO may be on the way out"),
        EventType.STAR_CEO to listOf("Headhunters said to be courting a star exec for {name}"),
        EventType.LAWSUIT_WON to listOf("Sources: {name} legal fight could end in its favor"),
        EventType.LAWSUIT_LOST to listOf("Sources: {name} bracing for adverse ruling"),
        EventType.SECONDARY_OFFERING to listOf("Sources: {name} weighing a share sale"),
        EventType.BUYBACK to listOf("Sources: {name} board discussing buyback"),
        EventType.BUYOUT_OFFER to listOf("Sources: {name} exploring a sale", "Private equity said to be circling {name}"),
    )

    private val sectorGood: Map<Sector, List<String>> = mapOf(
        Sector.TECH to listOf("Chip supply loosens; tech stocks rally", "AI hype cycle enters phase 7: tech soars"),
        Sector.CONSUMER to listOf("Shoppers go wild: consumer stocks jump on spending data", "Holiday sales smash forecasts"),
        Sector.ENERGY to listOf("Oil prices surge on supply fears; energy rallies", "Heatwave lifts power demand; energy stocks climb"),
        Sector.PHARMA to listOf("Regulators speed up approvals; pharma rallies", "Pharma climbs as aging population 'discovered' by analysts"),
        Sector.FINANCE to listOf("Banks rally on bumper trading revenues", "Financials climb as loan demand rebounds"),
        Sector.INDUSTRIAL to listOf("Infrastructure bill passes; industrials rally", "Factory orders boom; industrials climb"),
    )
    private val sectorBad: Map<Sector, List<String>> = mapOf(
        Sector.TECH to listOf("Chip shortage hits tech", "Tech sells off as 'the metaverse' fails to show up again"),
        Sector.CONSUMER to listOf("Consumers tighten belts; retail slides", "Spending data disappoints; consumer names fall"),
        Sector.ENERGY to listOf("Oil glut sinks energy stocks", "Windless week and cheap crude weigh on energy"),
        Sector.PHARMA to listOf("Drug pricing reform rattles pharma", "Pharma slides on regulatory crackdown"),
        Sector.FINANCE to listOf("Bank stocks slide on credit worries", "Financials fall as defaults tick up"),
        Sector.INDUSTRIAL to listOf("Supply chain snarls hit industrials", "Factory slowdown drags industrials lower"),
    )

    fun event(type: EventType, name: String, ticker: String, rng: Rng) = fill(rng.pick(events.getValue(type)), name, ticker)

    fun rumor(type: EventType, name: String, ticker: String, rng: Rng) =
        fill(rng.pick(rumors[type] ?: listOf("Unconfirmed reports swirl around {name}")), name, ticker)

    fun sector(sector: Sector, good: Boolean, rng: Rng) = rng.pick((if (good) sectorGood else sectorBad).getValue(sector))

    fun fill(template: String, name: String, ticker: String) = template.replace("{name}", name).replace("{ticker}", ticker)

    fun money(cents: Long): String = (if (cents < 0) "-$" else "$") + "%,.2f".format(kotlin.math.abs(cents) / 100.0)
}
