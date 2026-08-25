$ErrorActionPreference = "Stop"
$root = "D:\1\MC\Fm\FmKit"
$m2 = "C:\Users\Snowflake\.m2\repository"
$cp = ((Get-ChildItem $m2 -Recurse -Filter *.jar | Where-Object { $_.Name -notmatch 'sources|javadoc' }).FullName) -join ';'
$srcs = (Get-ChildItem "$root\src\main\java" -Recurse -Filter *.java).FullName
& javac --release 21 -encoding UTF-8 -cp $cp -d "$root\target\classes" $srcs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item "$root\src\main\resources\config.yml" "$root\target\classes\config.yml" -Force
Copy-Item "$root\src\main\resources\plugin.yml" "$root\target\classes\plugin.yml" -Force
& jar uf "$root\target\FmKit-1.0.0.jar" -C "$root\target\classes" .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "BUILD_OK"
