rootProject.name = "p2picks-backend"

include("app")
include("shared:kernel")
// Only modules with code are Gradle projects. Reserved module folders
// (identity, social, tables, chat, quickpicks, admin, sportsdata, audit)
// contain a .gitkeep until they receive code.
include(
    "modules:bets",
    "modules:settlement"
)
