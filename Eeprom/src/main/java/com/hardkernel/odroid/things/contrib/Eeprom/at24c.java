/*
 * Copyright 2023 Hardkernel Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hardkernel.odroid.things.contrib.Eeprom;

import static java.lang.Thread.sleep;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.I2cDevice;
import com.google.android.things.pio.PeripheralManager;

import java.io.IOException;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Basic Driver for I2c based AT24C series.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class at24c implements AutoCloseable {

    /**
     * delay time to read/write.
     */
    protected long wait_time = 0;
    private final int byte_length;
    protected byte wr_buffer_size;

    protected I2cDevice i2c;
    protected List<Gpio> address_gpio = new ArrayList<>();

    public boolean read_only;

    /**
     * at24c serieas's i2c address prefix value. it must be included on the i2c address of at24c.
     */
    public final int A_PREFIX = 0b1010000;

    public final int A000 = 0b000;
    public final int A001 = 0b001;
    public final int A010 = 0b010;
    public final int A011 = 0b011;
    public final int A100 = 0b100;
    public final int A101 = 0b101;
    public final int A110 = 0b110;
    public final int A111 = 0b111;

    /**
     * some kind of at24c device should 16 bit address space,or two byte address words on the i2c
     * message format. So it must be notified to android things frameworks.
     * the ODROID things suggested this feature by pass this value, 0x10000000.
     * when you attach this value with i2c address, the ODROID things frameworks automatically
     * use two word address on the i2c message.
     * Please at24c series implement must check this feature.
     */
    public final int addr_16 = 0x10000000;

    /**
     * at24c initialize with i2c bus, i2c bus address and at24c's size.
     * You can create to many size of at24c based driver.
     * but it is not yet support many type of it.
     * @param i2cBus i2c bus name on the android things.
     * @param bus_address i2c bus address of at24c series.
     * @param size device eeprom size.
     * @throws IOException exception on android things sequence of opening a i2c.
     */
    public at24c(String i2cBus, int bus_address, int size)
            throws IOException {
        // get Peripheral Manager for managing the i2c device.
        PeripheralManager manager = PeripheralManager.getInstance();

        initI2cBus(manager, i2cBus, bus_address);

        byte_length = size;
        read_only = false;
    }

    /**
     * at24c initialize with i2c bus, address gpios, i2c bus address and at24c's size.
     * You can create many size of at24c based driver.
     * You should connect address pins A0, A1, A2 to GPIOs.
     * and last 3 bits of address bit effect to the GPIOs.
     * @param i2cBus i2c bus name on the android things.
     * @param addressGpio gpio name string array for address pins.
     * @param bus_address i2c bus address of at24c series.
     * @param size device eeprom size.
     * @throws IOException exception on android things sequence of opening a i2c.
     */
    public at24c (String i2cBus, String[] addressGpio, int bus_address, int size)
        throws IOException, IllegalArgumentException {
        // get Peripheral Manager for managing the i2c device and address GPIOs.
        PeripheralManager manager = PeripheralManager.getInstance();

        initAddressGpios(manager, addressGpio);
        setAddressGpio(bus_address);
        initI2cBus(manager, i2cBus, bus_address);

        byte_length = size;
        read_only = false;

    }

    private void initI2cBus(PeripheralManager manager, String i2cBus, int address)
            throws IOException {
        /*
          get available i2c pin list.
          i2c name format - I2C-#, and n2/c4 have I2C-1 and I2C-2.
          In this case use given bus. if given bus is not in list, use default one.
         */
        List<String> i2cBusList = manager.getI2cBusList();
        if(i2cBusList.contains(i2cBus))
            i2c = manager.openI2cDevice(i2cBus, address);
        else
            i2c = manager.openI2cDevice(i2cBusList.get(0), address);
    }

    private void initAddressGpios(PeripheralManager manager, String[] gpios)
            throws IOException {
        List<String> gpioList = manager.getGpioList();

        for(int i=0; i < gpios.length; i++) {
            if (!gpioList.contains(gpios[i]))
                throw new IllegalArgumentException("gpio " + gpios[i] + "is not working");

            Gpio gpio = manager.openGpio(gpios[i]);
            gpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            address_gpio.add(i, gpio);
        }
    }

    private void setAddressGpio(int address) throws IOException {
        for(int i=0; i < address_gpio.size(); i++)
            address_gpio.get(i).setValue(isHigh(address, i));
    }

    private boolean isHigh(int bus_address, int index) throws IllegalArgumentException {
        if ((bus_address & A_PREFIX) != A_PREFIX)
            throw new IllegalArgumentException("Wrong address");

        return (bus_address & (1 << index)) != 0;
    }

    /**
     * Change address.
     * You must set address that be consist of A_PREFIX and target address.
     * if address gpio is not exist, it will not work.
     * @param address target at24c i2c bus address.
     * @throws IOException exception on android things sequence of opening a i2c.
     * @throws IllegalArgumentException address value is not start with A_PREX and not fit format.
     * @throws WrongMethodTypeException address gpio is not initialized.
     */
    public void changeAddress(int address)
            throws IOException, IllegalArgumentException, WrongMethodTypeException{
        if (address_gpio.isEmpty())
            throw new WrongMethodTypeException("you must init with address gpio");
        String i2cBus = i2c.getName();
        i2c.close();
        setAddressGpio(address);
        PeripheralManager manager = PeripheralManager.getInstance();
        initI2cBus(manager, i2cBus, address);
    }

    /**
     * read data from eeprom.
     * @param offset target offset for reading.
     * @param size data size bytes.
     * @return reading data.
     * @throws IOException error when reading from eeprom.
     * @throws IllegalArgumentException caused when data size argument is 0.
     * @throws IndexOutOfBoundsException caused when offset and size sum is bigger then eeprom size.
     * @throws InterruptedException some interrupt can caused from i2c read.
     */
    public byte[] read(int offset, int size)
            throws IOException, IllegalArgumentException,
            IndexOutOfBoundsException, InterruptedException {
        if (size == 0)
            throw new IllegalArgumentException("size must bigger then zero");
        if (offset + size > byte_length)
            throw new IndexOutOfBoundsException("Denied access to out of size");

        return _read(offset, size);
    }

    protected byte[] _read(int offset, int size)
            throws IOException, InterruptedException {
        byte [] buffer = new byte[size];
        i2c.readRegBuffer(offset, buffer, size);
        sleep(wait_time);
        return buffer;
    }

    /**
     * write data to eeprom.
     * @param offset target offset for reading writing.
     * @param val data value array. must be bigger then zero, samller then eeprom size.
     * @param size val array's size. please check it's size.
     * @throws IOException error when writing from eeprom
     * @throws IllegalArgumentException caused when data size argument is 0
     * @throws IndexOutOfBoundsException caused when offset and size sum is bigger then eeprom size.
     * @throws InterruptedException some interrupt can caused from i2c write.
     * @throws UnsupportedOperationException caused when read only state is on.
     */
    public void write (int offset, byte[] val, int size)
            throws IOException, IllegalArgumentException,
            IndexOutOfBoundsException, InterruptedException,
            UnsupportedOperationException {
        if (read_only)
            throw new UnsupportedOperationException("Read Only");
        if (size == 0)
            throw new IllegalArgumentException("size must bigger then zero");
        if (offset + size > byte_length)
            throw new IndexOutOfBoundsException("Denied access to out of size");

        int idx = 0;
        int remain = size;
        while (remain >= wr_buffer_size) {
            _write(offset + idx,
                    Arrays.copyOfRange(val, idx, idx + wr_buffer_size),
                    wr_buffer_size);
            idx += wr_buffer_size;
            remain -= wr_buffer_size;
        }
        if (remain > 0)
            _write(offset + idx,
                    Arrays.copyOfRange(val, idx, idx + remain),
                    remain);
    }

    protected void _write(int offset, byte[] val, int size)
            throws InterruptedException, IOException {
        i2c.writeRegBuffer(offset, val, size);
        sleep(wait_time);
    }

    /**
     * close at24c device driver to control.
     * @throws IOException I2c bus command exception.
     */
    @Override
    public void close() throws IOException {
        i2c.close();
        if (!address_gpio.isEmpty()) {
            for(Gpio gpio: address_gpio)
                gpio.close();
        }
    }
}