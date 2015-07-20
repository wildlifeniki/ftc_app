package org.swerverobotics.library.thunking;

import com.qualcomm.robotcore.hardware.*;
import com.qualcomm.robotcore.util.*;

import org.swerverobotics.library.SynchronousOpMode;
import org.swerverobotics.library.exceptions.RuntimeInterruptedException;
import org.swerverobotics.library.exceptions.SwerveRuntimeException;

/**
 * An implementation of DcMotorController that talks to a non-thunking target implementation
 * by thunking all calls over to the loop thread and back gain. The implementation automatically
 * takes care of read and write device mode switching.
 */
public class ThunkingMotorController extends ThunkedBase implements DcMotorController
    {
    //----------------------------------------------------------------------------------------------
    // State
    //----------------------------------------------------------------------------------------------

    DcMotorController target;           // can only talk to him on the loop thread

    DeviceMode controllerMode = null;  // the last mode we know the controller to be in

    //----------------------------------------------------------------------------------------------
    // Construction
    //----------------------------------------------------------------------------------------------

    private ThunkingMotorController(DcMotorController target)
        {
        this.target = target;
        }

    static public ThunkingMotorController Create(DcMotorController target)
        {
        return target instanceof ThunkingMotorController ? (ThunkingMotorController)target : new ThunkingMotorController(target);
        }

    //----------------------------------------------------------------------------------------------
    // Dispatching
    //----------------------------------------------------------------------------------------------

    @Override protected void enterReadOperation() throws InterruptedException
        {
        this.switchToMode(DeviceMode.READ_ONLY);
        }

    @Override protected void enterWriteOperation() throws InterruptedException
        {
        this.switchToMode(DeviceMode.WRITE_ONLY);
        }

    /**
     * Switch the controller to either read-only or write-only device mode.
     *
     * We assume that the only spontaneous transitions are
     *      SWITCHING_TO_READ_MODE -> READ_MODE
     * and
     *      SWITCHING_TO_WRITE_MODE -> WRITE_MODE.
     * All other transitions only happen because we (or someone else) requests them.
     */
    private synchronized void switchToMode(DeviceMode newMode) throws InterruptedException
        {
        // Note: remember that in general the user code may choose to spawn worker threads.
        // Thus, we may have multiple, concurrent threads simultaneously trying to switch modes.
        // We deal with that by using synchronized public methods, allowing only one client in at
        // a time; this gives us a sequential sequence of modes we need the controller to be in.
        // This method, too, is synchronized, but that's paranoia.

        // If we don't currently know his mode, we need to ask the controller where we stand
        if (null == this.controllerMode)
            {
            this.controllerMode = this.getMotorControllerDeviceMode();
            }

        // If the controller is being stupid in returning a non-actual mode (the mock one was) 
        // then to heck with trying to keep him happy
        if (null == this.controllerMode)
            return;

        // We might have caught this guy mid transition. Wait until he settles down
        if (this.controllerMode == DeviceMode.SWITCHING_TO_READ_MODE ||
            this.controllerMode == DeviceMode.SWITCHING_TO_WRITE_MODE)
            {
            for (;;)
                {
                this.controllerMode = this.getMotorControllerDeviceMode();
                //
                if (this.controllerMode == DeviceMode.SWITCHING_TO_READ_MODE ||
                    this.controllerMode == DeviceMode.SWITCHING_TO_WRITE_MODE)
                    {
                    SynchronousOpMode.idleCurrentThread();
                    }
                else
                    break;
                }
            }

        // If he's read-write, then that's just dandy
        if (this.controllerMode == DeviceMode.READ_WRITE)
            return;

        // If he's not what we want, then ask him to switch him to what we want and
        // spin until he gets there.
        if (this.controllerMode != newMode)
            {
            this.setMotorControllerDeviceMode(newMode);
            do
                {
                SynchronousOpMode.idleCurrentThread();
                this.controllerMode = this.getMotorControllerDeviceMode();
                }
            while (this.controllerMode != newMode);
            }
        }

    //----------------------------------------------------------------------------------------------
    // DcMotorController interface
    //----------------------------------------------------------------------------------------------

    @Override public synchronized void setMotorControllerDeviceMode(final DcMotorController.DeviceMode mode)
    // setMotorControllerDeviceMode is neither a 'read' nor a 'write' operation; it's internal
        {
        class Thunk extends NonwaitingThunk
            {
            @Override protected void actionOnLoopThread()
                {
                target.setMotorControllerDeviceMode(mode);
                }
            }
        Thunk thunk = new Thunk();
        thunk.dispatch();

        // Required: right now we have no idea what mode the controller is in (we know what
        // we *asked* him to do, yes). Thus, our cached knowledge of his state is unknown.
        this.controllerMode = null;
        }

    @Override public synchronized DcMotorController.DeviceMode getMotorControllerDeviceMode()
    // getMotorControllerDeviceMode is neither a 'read' nor a 'write' operation; it's internal
        {
        class Thunk extends ResultableThunk<DeviceMode>
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getMotorControllerDeviceMode();
                }
            }
        Thunk thunk = new Thunk();
        thunk.dispatch();

        // Optimization: we may as well update our knowledge about the controller's state
        this.controllerMode = thunk.result;

        return thunk.result;
        }

    @Override public synchronized String getDeviceName()
        {
        return this.doReadOperation(new ResultableThunk<String>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getDeviceName();
                }
            });
        }

    @Override public synchronized int getVersion()
        {
        return this.doReadOperation(new ResultableThunk<Integer>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getVersion();
                }
            });
        }

    @Override public synchronized void close()
        {
        this.doWriteOperation(new NonwaitingThunk()
            {
            @Override protected void actionOnLoopThread()
                {
                target.close();
                }
            });
        }


    @Override public synchronized void setMotorChannelMode(final int channel, final DcMotorController.RunMode mode)
        {
        this.doWriteOperation(new NonwaitingThunk()
            {
            @Override protected void actionOnLoopThread()
                {
                target.setMotorChannelMode(channel, mode);
                }
            });
        }

    @Override public synchronized DcMotorController.RunMode getMotorChannelMode(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<DcMotorController.RunMode>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getMotorChannelMode(channel);
                }
            });
        }

    @Override public synchronized void setMotorPower(final int channel, final double power)
        {
        this.doWriteOperation(new NonwaitingThunk()
            {
            @Override protected void actionOnLoopThread()
                {
                target.setMotorPower(channel, power);
                }
            });
        }

    @Override public synchronized double getMotorPower(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<Double>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getMotorPower(channel);
                }
            });
        }

    @Override public synchronized void setMotorPowerFloat(final int channel)
        {
        this.doWriteOperation(new NonwaitingThunk()
            {
            @Override protected void actionOnLoopThread()
                {
                target.setMotorPowerFloat(channel);
                }
            });
        }

    @Override public synchronized boolean getMotorPowerFloat(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<Boolean>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getMotorPowerFloat(channel);
                }
            });
        }

    @Override public synchronized void setMotorTargetPosition(final int channel, final int position)
        {
        this.doWriteOperation(new NonwaitingThunk()
            {
            @Override protected void actionOnLoopThread()
                {
                target.setMotorTargetPosition(channel, position);
                }
            });
        }

    @Override public synchronized int getMotorTargetPosition(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<Integer>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getMotorTargetPosition(channel);
                }
            });
        }

    @Override public synchronized int getMotorCurrentPosition(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<Integer>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getMotorCurrentPosition(channel);
                }
            });
        }

    @Override public synchronized void setGearRatio(final int channel, final double ratio)
        {
        this.doWriteOperation(new NonwaitingThunk()
            {
            @Override protected void actionOnLoopThread()
                {
                target.setGearRatio(channel, ratio);
                }
            });
        }

    @Override public synchronized double getGearRatio(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<Double>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getGearRatio(channel);
                }
            });
        }

    @Override public synchronized void setDifferentialControlLoopCoefficients(final int channel, final DifferentialControlLoopCoefficients pid)
        {
        this.doWriteOperation(new NonwaitingThunk()
        {
        @Override protected void actionOnLoopThread()
            {
            target.setDifferentialControlLoopCoefficients(channel, pid);
            }
        });
        }

    @Override public synchronized DifferentialControlLoopCoefficients getDifferentialControlLoopCoefficients(final int channel)
        {
        return this.doReadOperation(new ResultableThunk<DifferentialControlLoopCoefficients>()
            {
            @Override protected void actionOnLoopThread()
                {
                this.result = target.getDifferentialControlLoopCoefficients(channel);
                }
            });
        }
    }