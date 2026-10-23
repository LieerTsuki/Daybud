param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& $Python (Join-Path $PSScriptRoot 'check_project_structure.py') --root $projectRoot
if ($LASTEXITCODE -ne 0) { throw 'Current project structure check failed.' }
