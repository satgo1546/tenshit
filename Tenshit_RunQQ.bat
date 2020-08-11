@powershell.exe explorer.exe shell:AppsFolder\$(Get-AppxPackage -name 903DB504.46618D74B1ECA ^| select -expandproperty PackageFamilyName)!Tencent.QQ
