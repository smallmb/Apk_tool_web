rootProject.name = "apktool-cli"
include(
    "brut.j.common", "brut.j.util", "brut.j.dir", "brut.j.xml", "brut.j.yaml",
    "brut.apktool:apktool-lib", "brut.apktool:apktool-cli"
    , "apktool-web"
)

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {}
    }
}
