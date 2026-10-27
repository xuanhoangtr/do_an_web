param([string]$PostgresTestUrl = "", [string]$PostgresTestUser = "releaseqa")
$ErrorActionPreference = "Stop"
$qaRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$qaPreviousDockerHost = $env:DOCKER_HOST
Push-Location $qaRoot
try {
    $qaJsTests = @(Get-ChildItem -LiteralPath "src/test/js" -Filter "*.test.cjs" -File |
        Sort-Object FullName | ForEach-Object { [IO.Path]::GetRelativePath($qaRoot, $_.FullName).Replace('\', '/') })
    if ($qaJsTests.Count -eq 0) { throw "No JavaScript regression tests were found under src/test/js" }
    & node --test @qaJsTests
    if ($LASTEXITCODE -ne 0) { throw "JavaScript regression failed" }
    $qaCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot identify source commit" }
    $qaState = if (& git status --porcelain --untracked-files=normal) { "DIRTY" } else { "CLEAN" }
    $qaFiles = @("pom.xml", "mvnw", "mvnw.cmd", "scripts/build-release.ps1") + @(
        Get-ChildItem -LiteralPath src/main,.mvn -File -Recurse | ForEach-Object {
            [IO.Path]::GetRelativePath($qaRoot, $_.FullName).Replace('\', '/')
        })
    $qaManifest = ($qaFiles | Sort-Object -Unique | ForEach-Object {
        "$_`t$((Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant())"
    }) -join "`n"
    $qaSha = [Security.Cryptography.SHA256]::Create()
    try { $qaDigest = [Convert]::ToHexString($qaSha.ComputeHash([Text.Encoding]::UTF8.GetBytes($qaManifest))).ToLowerInvariant() }
    finally { $qaSha.Dispose() }
    # Use Docker Desktop's selected pipe instead of guessing docker_engine.
    if (!$env:DOCKER_HOST) {
        $qaDockerEndpoint = (& docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
        if ($LASTEXITCODE -ne 0) { throw "Cannot resolve the Docker test endpoint" }
        $env:DOCKER_HOST = $qaDockerEndpoint
    }
    $qaArgs = @("clean", "verify", "-Drelease.source.digest=$qaDigest", "-Drelease.source.state=$qaState", "-Drelease.source.commit=$qaCommit")
    if ($PostgresTestUrl) {
        $qaArgs += "-Drelease.qa.postgres.url=$PostgresTestUrl", "-Drelease.qa.postgres.user=$PostgresTestUser"
        $qaArgs += "-Dutewedding.p1.postgres.url=$PostgresTestUrl", "-Dutewedding.p1.postgres.username=$PostgresTestUser"
    }
    & ./mvnw.cmd @qaArgs
    if ($LASTEXITCODE -ne 0) { throw "Release verification failed; artifact is not ready for handoff" }
    $qaSkippedTests = @()
    foreach ($qaReport in Get-ChildItem -LiteralPath target/surefire-reports -Filter 'TEST-*.xml') {
        [xml]$qaResults = Get-Content -LiteralPath $qaReport.FullName -Raw
        foreach ($qaCase in $qaResults.testsuite.testcase) {
            if ($qaCase.skipped) {
                $qaReason = if ($qaCase.skipped.message) { $qaCase.skipped.message } else { "No skip reason recorded" }
                $qaSkippedTests += "$($qaResults.testsuite.name).$($qaCase.name): $qaReason"
            }
        }
    }
    if ($qaSkippedTests.Count -gt 0) {
        throw "Release verification skipped tests: $($qaSkippedTests -join '; ')"
    }
    $qaJar = Join-Path $qaRoot "target/utewedding-app-0.1.0-SNAPSHOT.jar"
    $qaJarHash = (Get-FileHash -LiteralPath $qaJar -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$qaJar.sha256", "$qaJarHash  $([IO.Path]::GetFileName($qaJar))`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $qaRoot "target/release-source-manifest.txt"), $qaManifest, [Text.UTF8Encoding]::new($false))
    Write-Output "Source state: $qaState; base commit: $qaCommit; source SHA256: $qaDigest; JAR SHA256: $qaJarHash"
} finally { $env:DOCKER_HOST = $qaPreviousDockerHost; Pop-Location }
