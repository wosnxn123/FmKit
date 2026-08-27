$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$m2 = if ($env:M2_REPO) { $env:M2_REPO } else { Join-Path $env:USERPROFILE ".m2\repository" }
if (-not (Test-Path $m2)) { throw "Maven repository not found: $m2 (set M2_REPO to override)" }
$cp = ((Get-ChildItem $m2 -Recurse -Filter *.jar | Where-Object { $_.Name -notmatch 'sources|javadoc' }).FullName) -join ';'
$srcs = (Get-ChildItem "$root\src\main\java" -Recurse -Filter *.java).FullName
& javac --release 21 -encoding UTF-8 -cp $cp -d "$root\target\classes" $srcs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item "$root\src\main\resources\config.yml" "$root\target\classes\config.yml" -Force
Copy-Item "$root\src\main\resources\prices.yml" "$root\target\classes\prices.yml" -Force
Copy-Item "$root\src\main\resources\plugin.yml" "$root\target\classes\plugin.yml" -Force
& jar cf "$root\target\FmShop-1.0.0.jar" -C "$root\target\classes" .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "BUILD_OK"
