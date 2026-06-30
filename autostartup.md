old post from a forum in 2011, hopefully this advice still works, minus the removing systemui part 

* root the device. you can find instructions online. please do this
at your own risk and. IT WILL VOID YOUR MANUFACTURERS GUARANTEE!

* once rooted, you can use adb shell, or some other method to delete
or rename the SystemUI package under /system/apps. This will make it
so the system ui bar at teh bottom with the home button etc, will be
permanently removed

* if you want your application to autostart (instead of the launcher)
you can add the following categories to the xml config of your app
(youl be asked which one to launch by default once, and can reset it
in system settings):

```xml
<action android:name="android.intent.action.MAIN" />
<category android:name="android.intent.category.LAUNCHER" />
<category android:name="android.intent.category.HOME" />
<category android:name="android.intent.category.DEFAULT" />
```

* using os.system(), you can call execute the built in "am" program to
start other programs, to e.g. switch to settings if you want some
"secret" way to exit the app (I also just found this
link:http://android.serverbox.ch/?p=306, looks liek maybe you don't
have to remove SystemUI completely but can start/stop it using "am" as
well)
