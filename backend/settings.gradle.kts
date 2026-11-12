rootProject.name = "p2picks-backend"

include("app")
include("shared:kernel")
include(
    "modules:identity",
    "modules:social",
    "modules:tables",
    "modules:chat",
    "modules:bets",
    "modules:settlement",
    "modules:quickpicks",
    "modules:notifications",
    "modules:admin",
    "modules:sportsdata",
    "modules:audit"
)
